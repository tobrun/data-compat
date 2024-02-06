package com.tobrun.datacompat

import com.google.devtools.ksp.isPrivate
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.KSVisitorVoid
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.validate
import com.squareup.kotlinpoet.ANY
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asTypeName
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.toTypeParameterResolver
import com.squareup.kotlinpoet.ksp.writeTo
import com.tobrun.datacompat.annotation.DataCompat
import com.tobrun.datacompat.annotation.Default
import java.util.Locale

/**
 * [DataCompatProcessor] is a concrete instance of the [SymbolProcessor] interface.
 * This processor supports multiple round execution, it may return a list of deferred DataCompat annotated symbols.
 * Exceptions or implementation errors will result in a termination of processing immediately and be logged as an error
 * in KSPLogger.
 */
class DataCompatProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {

    override fun process(resolver: Resolver): List<KSAnnotated> {
        logger.info("DataCompat: process")
        val dataCompatAnnotated =
            resolver.getSymbolsWithAnnotation(DataCompat::class.qualifiedName!!, true)
        if (dataCompatAnnotated.count() == 0) {
            logger.info("DataCompat: No DataCompat annotations found for processing")
            return emptyList()
        }

        // symbols returned by
        // resolver.getSymbolsWithAnnotation(DataCompat::class.qualifiedName!!, true)
        // don't expose their annotations.
        // Therefore we can't access the @Default annotation required to build the code
        // for default values.
        val classToDefaultValuesMap =
            mutableMapOf<KSClassDeclaration, MutableMap<String, String?>>()

        val symbolsWithDefaultAnnotation =
            resolver.getSymbolsWithAnnotation(Default::class.qualifiedName!!, true)
        symbolsWithDefaultAnnotation.forEach { annotatedProperty ->
            // since KSP 1.8.10-1.0.9 annotatedProperty are returned ONLY as KSValueParameter;
            // for previous KSP versions they were also returned as KSPropertyDeclaration
            if (annotatedProperty is KSValueParameter) {
                val parentClass = annotatedProperty.findParentClass()!!
                var defaultValueMap = classToDefaultValuesMap[parentClass]
                if (defaultValueMap == null) {
                    defaultValueMap = mutableMapOf()
                }
                val defaultAnnotationsParams =
                    annotatedProperty.annotations.firstOrNull()?.arguments
                val defaultValue = defaultAnnotationsParams?.first()
                defaultValueMap[annotatedProperty.name!!.getShortName()] =
                    defaultValue?.value as? String?
                classToDefaultValuesMap[parentClass] = defaultValueMap
            }
        }

        val unableToProcess = dataCompatAnnotated.filterNot { it.validate() }
        dataCompatAnnotated.filter { it is KSClassDeclaration && it.validate() }
            .forEach { it.accept(Visitor(classToDefaultValuesMap), Unit) }
        return unableToProcess.toList()
    }

    private fun KSNode.findParentClass(): KSClassDeclaration? {
        var currentParent: KSNode? = parent
        while (currentParent !is KSClassDeclaration) {
            currentParent = parent?.parent
            if (currentParent == null) {
                return null
            }
        }
        return currentParent
    }

    private inner class Visitor(
        private val defaultValuesMap: Map<KSClassDeclaration, MutableMap<String, String?>>,
    ) : KSVisitorVoid() {

        @Suppress("LongMethod", "MaxLineLength", "ComplexMethod")
        override fun visitClassDeclaration(classDeclaration: KSClassDeclaration, data: Unit) {
            if (isInvalidAnnotatedSetup(classDeclaration)) {
                return
            }

            // Cleanup class name by dropping Data part
            // TODO make this part more flexible with providing name inside the annotation
            val className =
                classDeclaration.simpleName.asString().dropLast(CLASS_NAME_DROP_LAST_CHARACTERS)
            val classKdoc = classDeclaration.docString
            val packageName = classDeclaration.packageName.asString()

            val dataCompatAnnotation = classDeclaration.annotations.firstOrNull {
                it.annotationType.resolve().toString() == DataCompat::class.simpleName
            }
            // Resolve list of imports from [DataCompat.importsForDefaults]
            val imports = ArrayList<String>()
            dataCompatAnnotation?.arguments?.firstOrNull {
                it.name?.getShortName() == "importsForDefaults"
            }?.value?.let { imports.addAll(it as ArrayList<String>) }

            // Resolve generateCompanionObject flag from [DataCompat.generateCompanionObject]
            var generateCompanionObject = false
            dataCompatAnnotation?.arguments?.firstOrNull {
                it.name?.getShortName() == "generateCompanionObject"
            }?.value?.let { generateCompanionObject = it as Boolean }

            val otherAnnotations = classDeclaration.annotations
                .filter { it.annotationType.resolve().toString() != DataCompat::class.simpleName }
            val implementedInterfaces = classDeclaration
                .superTypes
                .filter { (it.resolve().declaration as? KSClassDeclaration)?.classKind == ClassKind.INTERFACE }

            // Map KSP properties with KoltinPoet TypeNames
            val propertyMap = mutableMapOf<KSPropertyDeclaration, PropertyDescriptor>()
            for (property in classDeclaration.getAllProperties()) {
                val classTypeParams = classDeclaration.typeParameters.toTypeParameterResolver()
                val typeName = property.type.resolve().toTypeName(classTypeParams)
                propertyMap[property] = PropertyDescriptor(
                    typeName = typeName,
                    mandatoryForConstructor = defaultValuesMap[classDeclaration]
                        ?.get(property.toString()) == null && !typeName.isNullable,
                    kDoc = property.docString?.trim(' ', '\n') ?: property.toString()
                        .capitalizeAndAddSpaces(),
                )
            }

            // Build mandatory param list for toBuilder and DSL function
            val mandatoryParams = propertyMap.filter {
                it.value.mandatoryForConstructor
            }.map { it.key.toString() }.joinToString(", ")

            // KotlinPoet class builder
            val classBuilder = TypeSpec.classBuilder(className).apply {
                classKdoc?.let {
                    addKdoc(
                        classKdoc.split("\n")
                            .filter { it.isNotEmpty() }.joinToString(
                                separator = "\n",
                                transform = {
                                    if (it.startsWith(" ")) {
                                        it.substring(1)
                                    } else {
                                        it
                                    }
                                }
                            )
                    )
                }

                otherAnnotations.forEach {
                    addAnnotation(
                        it.annotationType.resolve().toClassName()
                    )
                }

                implementedInterfaces.forEach {
                    addSuperinterface(
                        it.resolve().toClassName()
                    )
                }

                // Constructor
                val constructorBuilder = FunSpec.constructorBuilder()
                constructorBuilder.addModifiers(KModifier.PRIVATE)
                for (entry in propertyMap) {
                    constructorBuilder.addParameter(entry.key.toString(), entry.value.typeName)
                }
                primaryConstructor(constructorBuilder.build())

                // Property initializers
                for (entry in propertyMap) {
                    addProperty(
                        PropertySpec.builder(entry.key.toString(), entry.value.typeName)
                            .addKdoc(
                                """
                                |${entry.value.kDoc}
                                """.trimMargin()
                            )
                            .initializer(entry.key.toString())
                            .build()
                    )
                }

                // Function toString
                addFunction(
                    FunSpec.builder("toString")
                        .addModifiers(KModifier.OVERRIDE)
                        .addKdoc(
                            """
                            Overloaded toString function.
                            """.trimIndent()
                        )
                        // using triple quote for long strings
                        .addStatement(
                            propertyMap.keys.joinToString(
                                prefix = "return \"\"\"$className(",
                                transform = { "$it=$$it" },
                                postfix = ")\"\"\".trimIndent()"
                            )
                        )
                        .build()
                )

                // Function equals
                val equalsBuilder = FunSpec.builder("equals")
                    .addModifiers(KModifier.OVERRIDE)
                    .addKdoc(
                        """
                        Overloaded equals function.
                        """.trimIndent()
                    )
                    .addParameter("other", ANY.copy(nullable = true))
                    .addStatement("if (this === other) return true")
                    .addStatement("if (javaClass != other?.javaClass) return false")
                    .addStatement("other as $className")
                    .addStatement(
                        propertyMap.keys.joinToString(
                            prefix = "return ",
                            separator = "·&& ",
                            transform = {
                                with(it.type.resolve()) {
                                    val isFloat =
                                        toClassName() == Float::class.asTypeName()
                                    val isDouble =
                                        toClassName() == Double::class.asTypeName()
                                    when {
                                        !isMarkedNullable && (isDouble || isFloat) -> "$it.compareTo(other.$it)·==·0"
                                        isMarkedNullable && isDouble -> "($it·?:·0.0).compareTo(other.$it·?:·0.0)·==·0"
                                        isMarkedNullable && isFloat -> "($it·?:·0f).compareTo(other.$it·?:·0f)·==·0"
                                        else -> "$it·==·other.$it"
                                    }
                                }
                            },
                            postfix = ""
                        )
                    )
                    .returns(Boolean::class)
                addFunction(equalsBuilder.build())

                // Function hashCode
                addFunction(
                    FunSpec.builder("hashCode")
                        .addKdoc(
                            """
                            Overloaded hashCode function based on all class properties.
                            """.trimIndent()
                        )
                        .addModifiers(KModifier.OVERRIDE)
                        .addStatement(
                            propertyMap.keys.joinToString(
                                prefix = "return Objects.hash(",
                                separator = ", ",
                                postfix = ")"
                            )
                        )
                        .returns(Int::class)
                        .build()
                )

                // Function toBuilder
                addFunction(
                    FunSpec.builder("toBuilder")
                        .addKdoc(
                            """
                            Convert to Builder allowing to change class properties.
                            """.trimIndent()
                        )
                        .addStatement(
                            propertyMap.keys.joinToString(
                                prefix = "return Builder($mandatoryParams)\n",
                                transform = { str ->
                                    "${" ".repeat(INDENTATION_SIZE)}.set${
                                    str.toString().replaceFirstChar {
                                        if (it.isLowerCase())
                                            it.titlecase(Locale.getDefault())
                                        else it.toString()
                                    }
                                    }($str)"
                                },
                                separator = "\n",
                            )
                        )
                        .returns(ClassName("", "Builder"))
                        .build()
                )
            }

            // Builder pattern
            val builderBuilder = TypeSpec.classBuilder("Builder")
            var builderConstructorNeeded = false
            val constructorBuilder = FunSpec.constructorBuilder()
            for (property in propertyMap) {
                val propertyName = property.key.toString()
                // when no default value provided but property is non nullable -
                // it should be moved to Builder mandatory ctor arguments
                if (property.value.mandatoryForConstructor) {
                    builderConstructorNeeded = true
                    constructorBuilder.addParameter(
                        propertyName,
                        property.value.typeName,
                        KModifier.PUBLIC
                    )
                    builderBuilder.addProperty(
                        PropertySpec.builder(propertyName, property.value.typeName)
                            .initializer(propertyName)
                            .addKdoc(
                                """
                            |${property.value.kDoc}
                            """.trimMargin()
                            )
                            .addAnnotation(
                                AnnotationSpec.builder(JvmSynthetic::class)
                                    .useSiteTarget(AnnotationSpec.UseSiteTarget.SET)
                                    .build()
                            )
                            .mutable(true)
                            .build()
                    )
                } else {
                    builderBuilder.addProperty(
                        PropertySpec.builder(propertyName, property.value.typeName)
                            .initializer(
                                CodeBlock.builder()
                                    .add(
                                        defaultValuesMap[classDeclaration]?.get(propertyName)
                                            ?: "null"
                                    )
                                    .build()
                            )
                            .addKdoc(
                                """
                            |${property.value.kDoc}
                            """.trimMargin()
                            )
                            .addAnnotation(
                                AnnotationSpec.builder(JvmSynthetic::class)
                                    .useSiteTarget(AnnotationSpec.UseSiteTarget.SET)
                                    .build()
                            )
                            .mutable()
                            .build()
                    )
                }

                builderBuilder.addFunction(
                    FunSpec
                        .builder(
                            "set${
                            propertyName.replaceFirstChar {
                                if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
                            }
                            }"
                        )
                        .addKdoc(
                            """
                            |Setter for $propertyName: ${
                            property.value.kDoc.trimEnd('.')
                                .replaceFirstChar { it.lowercase(Locale.getDefault()) }
                            }.
                            |
                            |@param $propertyName
                            |@return Builder
                            """.trimMargin()
                        )
                        .addParameter(propertyName, property.value.typeName)
                        .addStatement("this.$propertyName = $propertyName")
                        .addStatement("return this")
                        .returns(ClassName(packageName, className, "Builder"))
                        .build()
                )
            }
            if (builderConstructorNeeded) {
                builderBuilder.primaryConstructor(constructorBuilder.build())
            }

            val buildFunction = FunSpec.builder("build")
            buildFunction.addKdoc(
                """
                |Returns a [$className] reference to the object being constructed by the builder.
                |
                |@return $className
                """.trimMargin()
            )
            buildFunction.addStatement(
                propertyMap.keys.joinToString(
                    prefix = "return $className(",
                    transform = { "$it" },
                    separator = ", ",
                    postfix = ")"
                )
            )
                .returns(ClassName(packageName, className))

            builderBuilder.addKdoc(
                """
                |Composes and builds a [$className] object.
                |
                |This is a concrete implementation of the builder design pattern.
                """.trimMargin()
            )
            builderBuilder.addFunction(buildFunction.build())

            classBuilder.addType(builderBuilder.build())

            if (generateCompanionObject) {
                classBuilder.addType(
                    TypeSpec.companionObjectBuilder()
                        .addKdoc(
                            """
                            Public Companion Object of [$className].
                            """.trimIndent()
                        ).build()
                )
            }

            // initializer function
            val initializerFunctionBuilder = FunSpec.builder(className)
                .addKdoc(
                    """
                    |Creates a [$className] through a DSL-style builder.
                    |
                    |@param initializer the initialisation block
                    |@return $className
                    """.trimMargin()
                )
                .returns(ClassName(packageName, className))
                .addAnnotation(JvmSynthetic::class)

            if (mandatoryParams.isNotEmpty()) {
                propertyMap.filter {
                    it.value.mandatoryForConstructor
                }.forEach {
                    initializerFunctionBuilder.addParameter(it.key.toString(), it.value.typeName)
                }
            }

            initializerFunctionBuilder.addParameter(
                ParameterSpec.builder(
                    "initializer",
                    LambdaTypeName.get(
                        ClassName(packageName, className, "Builder"),
                        emptyList(),
                        ClassName("kotlin", "Unit")
                    )
                ).build()
            ).addStatement("return $className.Builder($mandatoryParams).apply(initializer).build()")

            // File
            val fileBuilder = FileSpec.builder(packageName, className)
                .addImport("java.util", "Objects")
                .suppressWarningTypes("RedundantVisibilityModifier")
                .addType(classBuilder.build())
                .addFunction(initializerFunctionBuilder.build())

            imports.forEach {
                fileBuilder
                    .addImport(
                        it.split(".").dropLast(1).joinToString("."),
                        it.split(".").last()
                    )
            }

            fileBuilder.build().writeTo(codeGenerator = codeGenerator, aggregating = false)
        }

        @Suppress("SameParameterValue")
        private fun FileSpec.Builder.suppressWarningTypes(vararg types: String): FileSpec.Builder {
            if (types.isEmpty()) {
                return this
            }

            val format = "%S,".repeat(types.count()).trimEnd(',')
            addAnnotation(
                AnnotationSpec.builder(ClassName("", "Suppress"))
                    .addMember(format, *types)
                    .build()
            )
            return this
        }

        @Suppress("ReturnCount")
        private fun isInvalidAnnotatedSetup(classDeclaration: KSClassDeclaration): Boolean {
            val qualifiedName = classDeclaration.qualifiedName?.asString() ?: run {
                logger.error(
                    "@DataClass must target classes with a qualified name",
                    classDeclaration
                )
                return true
            }

            if (!classDeclaration.isDataClass()) {
                logger.error(
                    "@DataClass cannot target a non-data class $qualifiedName",
                    classDeclaration
                )
                return true
            }

            if (!classDeclaration.isPrivate()) {
                logger.error(
                    "@DataClass target must have private visibility",
                    classDeclaration
                )
                return true
            }

            if (classDeclaration.typeParameters.any()) {
                logger.error(
                    "@DataClass target shouldn't have type parameters",
                    classDeclaration
                )
                return true
            }

            if (!classDeclaration.simpleName.asString().endsWith("Data")) {
                logger.error(
                    "@DataClass target must end with Data suffix naming",
                    classDeclaration
                )
                return true
            }
            return false
        }
    }

    private fun KSClassDeclaration.isDataClass() = modifiers.contains(Modifier.DATA)

    private fun String.capitalizeAndAddSpaces(): String {
        val tmpStr = replace(Regex("[A-Z]")) { " " + it.value.lowercase(Locale.getDefault()) }
        return tmpStr.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
        } + "."
    }

    private companion object {
        private const val CLASS_NAME_DROP_LAST_CHARACTERS = 4
        private const val INDENTATION_SIZE = 2
    }
}

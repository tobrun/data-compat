package com.tobrun.datacompat

import com.tschuchort.compiletesting.SourceFile

internal val testAnnotation = SourceFile.kotlin(
    "TestAnnotation.kt",
"""
package com.tobrun.datacompat.annotation

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class DataCompat(
    val importsForDefaults: Array<String> = [],
    val generateCompanionObject: Boolean = false
)

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.VALUE_PARAMETER)
annotation class Default(val valueAsString: String)
    """.trimIndent()
)

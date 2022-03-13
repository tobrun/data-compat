## Kotlin-data-compat

### Context
The Data-compat library resolves the Java binary incompatibility that library developers face when using Kotlin data classes. In a nutshell, every change to a data class results in major breaking change. This project attempts to resolve this binary incompatibility by using annotation processing. Users can keep using their original data classes definitions, but the generated code will compatible for Java consumption.
To read more about this incompatibility, please refer to Jake Wharton's [Public API challenges in Kotlin](https://jakewharton.com/public-api-challenges-in-kotlin/) blogpost.

This library uses [Kotlin Symbol Processing API (KSP)](https://kotlinlang.org/docs/ksp-overview.html) in combination with [KotlinPoet](https://square.github.io/kotlinpoet/) to generate Kotlin classes. Input is a private data class that supports a `@DataCompat` annotation and the code generator outputs a Kotlin class that supports a builder pattern compatible for Java usage.

### Add to your project
The project is hosted on [jitpack](https://jitpack.io/) and requires to add jitpack.io lookup to your gradle configuration:

```groovy
allprojects {
  repositories {
    maven { url 'https://jitpack.io' }
  }
}
```

Since this project uses ksp, you will have to include it:
```groovy
plugins {
  id 'com.google.devtools.ksp' version '1.6.10-1.0.2' apply false
}
```

And you will have to include the required dependencies:

```groovy
dependencies {
  implementation 'com.github.tobrun.kotlin-data-compat:annotation:0.1.0'
  ksp 'com.github.tobrun.kotlin-data-compat:processor:0.1.0'
}
```


### Getting started

Given an exisiting data class:
 - add `@DataCompat` annotation
 - mark class private
 - Append `Data` to the class name

For example:

```kotlin
/**
 * Represents a person.
 * @property name The full name.
 * @property nickname The nickname.
 * @property age The age.
 */
@DataCompat
private data class PersonData(val name: String, val nickname: String? = null, val age: Int)
```

After compilation, the following class will be generated:

```kotlin
/**
 * Represents a person.
 * @property name The full name.
 * @property nickname The nickname.
 * @property age The age.
 */
public class Person private constructor(
  public val name: String,
  public val nickname: String?,
  public val age: Int
) {
  public override fun toString() = "Person(name=$name, nickname=$nickname, age=$age)"

  public override fun equals(other: Any?): Boolean = other is Person
  		&& name == other.name
  		&& nickname == other.nickname
  		&& age == other.age

  public override fun hashCode(): Int = Objects.hash(name, nickname, age)

  /**
   * Composes and builds a [Person] object.
   *
   * This is a concrete implementation of the builder design pattern.
   *
   * @property name The full name.
   * @property nickname The nickname.
   * @property age The age.
   */
  public class Builder {
    @set:JvmSynthetic
    public var name: String? = null

    @set:JvmSynthetic
    public var nickname: String? = null

    @set:JvmSynthetic
    public var age: Int? = null

    /**
     * Set the full name.
     *
     * @param name the full name.
     * @return Builder
     */
    public fun setName(name: String?): Builder {
      this.name = name
      return this
    }

    /**
     * Set the nickname.
     *
     * @param nickname the nickname.
     * @return Builder
     */
    public fun setNickname(nickname: String?): Builder {
      this.nickname = nickname
      return this
    }

    /**
     * Set the age.
     *
     * @param age the age.
     * @return Builder
     */
    public fun setAge(age: Int?): Builder {
      this.age = age
      return this
    }

    /**
     * Returns a [Person] reference to the object being constructed by the builder.
     *
     * Throws an [IllegalArgumentException] when a non-null property wasn't initialised.
     *
     * @return Person
     */
    public fun build(): Person {
      if (name==null) {
      	throw IllegalArgumentException("Null name found when building Person.")
      }
      if (age==null) {
      	throw IllegalArgumentException("Null age found when building Person.")
      }
      return Person(name!!, nickname, age!!)
    }
  }
}

/**
 * Creates a [Person] through a DSL-style builder.
 *
 * @param initializer the intialisation block
 * @return Person
 */
@JvmSynthetic
public fun Person(initializer: Person.Builder.() -> Unit): Person =
    Person.Builder().apply(initializer).build()
```

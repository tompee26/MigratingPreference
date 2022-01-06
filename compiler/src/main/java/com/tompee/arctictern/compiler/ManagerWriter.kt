package com.tompee.arctictern.compiler

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.SET
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.ksp.toKModifier
import com.tompee.arctictern.compiler.extensions.toNullable

internal class ManagerWriter(
    private val classDeclaration: KSClassDeclaration,
    private val fileSpecs: Map<FileSpec, KSClassDeclaration>
) {

    companion object {

        private const val MANAGER_NAME = "ArcticTernManager"
    }

    private val managerTypeName =
        ClassName(classDeclaration.packageName.asString(), MANAGER_NAME)

    fun createManager(): FileSpec {
        return FileSpec.builder(classDeclaration.packageName.asString(), MANAGER_NAME)
            .addType(buildClass())
            .build()
    }

    /**
     * Build the class. The hierarchy will be
     * Manager class with constructor
     *   Companion object
     *     Private instance
     *     Double-check getters
     *   Generator functions
     */
    private fun buildClass(): TypeSpec {
        return TypeSpec.classBuilder(MANAGER_NAME)
            .addModifiers(listOfNotNull(classDeclaration.getVisibility().toKModifier()))
            .applyConstructor()
            .applyCompanionObject()
            .applyGeneratorFunctions()
            .applyMigration()
            .build()
    }

    /**
     * Applies the constructor and properties. Contains the context as a parameter
     */
    private fun TypeSpec.Builder.applyConstructor(): TypeSpec.Builder {
        return primaryConstructor(
            FunSpec.constructorBuilder()
                .addModifiers(KModifier.PRIVATE)
                .addParameter(contextField.toParameterSpec())
                .build()
        ).addProperty(
            contextField
                .toPropertySpecBuilder(true, KModifier.PRIVATE)
                .build()
        )
    }

    /**
     * Applies the companion object
     */
    private fun TypeSpec.Builder.applyCompanionObject(): TypeSpec.Builder {
        return addType(
            TypeSpec.companionObjectBuilder()
                .addProperty(
                    PropertySpec.builder("instance", managerTypeName.toNullable(true))
                        .mutable(true)
                        .addModifiers(KModifier.PRIVATE)
                        .initializer("null")
                        .build()
                )
                .addFunction(
                    FunSpec.builder("getInstance")
                        .returns(managerTypeName)
                        .addParameter(contextField.name, contextField.type)
                        .addCode(
                            CodeBlock.builder()
                                .addStatement("val actualInstance = instance")
                                .addStatement("if (actualInstance != null) return actualInstance")
                                .beginControlFlow("return synchronized(this)")
                                .addStatement("val doubleCheckInstance = instance")
                                .beginControlFlow("doubleCheckInstance ?: run ")
                                .addStatement("val newInstance = $MANAGER_NAME(context)")
                                .addStatement("instance = newInstance")
                                .addStatement("newInstance")
                                .endControlFlow()
                                .endControlFlow()
                                .build()
                        )
                        .build()
                )
                .build()
        )
    }

    /**
     * Applies the generator functions
     */
    private fun TypeSpec.Builder.applyGeneratorFunctions(): TypeSpec.Builder {
        return addFunctions(
            fileSpecs.map { (spec, clazz) ->
                listOf(
                    FunSpec.builder("create${clazz.simpleName.asString()}")
                        .addModifiers(listOfNotNull(clazz.getVisibility().toKModifier()))
                        .returns(ClassName(spec.packageName, spec.name))
                        .addStatement("return %L(context)", spec.name)
                        .build(),
                    FunSpec.builder("create${spec.name}")
                        .addModifiers(listOfNotNull(clazz.getVisibility().toKModifier()))
                        .returns(ClassName(spec.packageName, spec.name))
                        .addStatement("return %L(context)", spec.name)
                        .build()
                )
            }.flatten()
        )
    }

    /**
     * Applies initialization and migration
     */
    private fun TypeSpec.Builder.applyMigration(): TypeSpec.Builder {
        return addProperty(
            PropertySpec.builder(
                "migratableSet",
                SET.parameterizedBy(migratableField.type),
                KModifier.PRIVATE
            )
                .getter(
                    FunSpec.getterBuilder()
                        .addStatement("val set = setOf<Migratable>(")
                        .apply {
                            fileSpecs.map { (spec, _) ->
                                "${spec.name}(${contextField.name}),"
                            }
                                .forEach { addStatement(it) }
                        }
                        .addStatement(")")
                        .addStatement("return set")
                        .build()
                )
                .build()
        )
            .addFunction(
                FunSpec.builder("migrate")
                    .beginControlFlow("migratableSet.forEach")
                    .addStatement("it.initialize()")
                    .addStatement("it.migrate()")
                    .endControlFlow()
                    .build()
            )
    }
}

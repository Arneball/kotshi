@file:Suppress("UnstableApiUsage")

package se.ansman.kotshi

import com.google.auto.common.MoreElements
import com.google.common.collect.SetMultimap
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.MemberName.Companion.member
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.STAR
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asClassName
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import se.ansman.kotshi.generators.jsonAdapterFactory
import java.lang.reflect.Type
import javax.annotation.processing.Filer
import javax.annotation.processing.Messager
import javax.annotation.processing.RoundEnvironment
import javax.lang.model.SourceVersion
import javax.lang.model.element.Element
import javax.lang.model.element.Modifier
import javax.lang.model.element.TypeElement
import javax.lang.model.type.TypeMirror
import javax.lang.model.util.Elements
import javax.lang.model.util.Types
import javax.tools.Diagnostic
import kotlin.reflect.KClass

class FactoryProcessingStep(
    override val processor: KotshiProcessor,
    private val messager: Messager,
    override val filer: Filer,
    private val types: Types,
    private val elements: Elements,
    private val sourceVersion: SourceVersion,
    private val adapters: List<GeneratedAdapter>
) : KotshiProcessor.GeneratingProcessingStep() {

    private fun TypeMirror.implements(someType: KClass<*>): Boolean =
        types.isSubtype(this, elements.getTypeElement(someType.java.canonicalName).asType())

    override val annotations: Set<Class<out Annotation>> = setOf(KotshiJsonAdapterFactory::class.java)

    override fun process(elementsByAnnotation: SetMultimap<Class<out Annotation>, Element>, roundEnv: RoundEnvironment) {
        val elements = elementsByAnnotation[KotshiJsonAdapterFactory::class.java]
        if (elements.size > 1) {
            messager.printMessage(Diagnostic.Kind.ERROR, "Multiple classes found with annotations @KotshiJsonAdapterFactory", elements.first())
        } else for (element in elements) {
            try {
                generateFactory(MoreElements.asType(element))
            } catch (e: ProcessingError) {
                messager.printMessage(Diagnostic.Kind.ERROR, "Kotshi: ${e.message}", e.element)
            }
        }
    }

    private fun generateFactory(element: TypeElement) {
        val elementClassName = element.asClassName()
        val generatedName = elementClassName.let {
            ClassName(it.packageName, "Kotshi${it.simpleNames.joinToString("_")}")
        }

        val typeSpecBuilder = if (element.asType().implements(JsonAdapter.Factory::class) && Modifier.ABSTRACT in element.modifiers) {
            TypeSpec.objectBuilder(generatedName)
                .superclass(elementClassName)
        } else {
            TypeSpec.objectBuilder(generatedName)
                .addSuperinterface(jsonAdapterFactory)
        }

        typeSpecBuilder
            .maybeAddGeneratedAnnotation(elements, sourceVersion)
            .addModifiers(KModifier.INTERNAL)
            .addOriginatingElement(element)

        val typeParam = ParameterSpec.builder("type", Type::class)
            .build()
        val annotationsParam = ParameterSpec.builder("annotations", Set::class.asClassName().parameterizedBy(Annotation::class.asClassName()))
            .build()
        val moshiParam = ParameterSpec.builder("moshi", Moshi::class)
            .build()

        val factory = typeSpecBuilder
            .addFunction(makeCreateFunction(typeParam, annotationsParam, moshiParam, adapters))
            .build()

        FileSpec.builder(generatedName.packageName, generatedName.simpleName)
            .addComment("Code generated by Kotshi. Do not edit.")
            .addType(factory)
            .build()
            .writeTo(filer)
    }

    private fun makeCreateFunction(
        typeParam: ParameterSpec,
        annotationsParam: ParameterSpec,
        moshiParam: ParameterSpec,
        adapters: List<GeneratedAdapter>
    ): FunSpec {
        val createSpec = FunSpec.builder("create")
            .addModifiers(KModifier.OVERRIDE)
            .returns(JsonAdapter::class.asClassName().parameterizedBy(STAR).nullable())
            .addParameter(typeParam)
            .addParameter(annotationsParam)
            .addParameter(moshiParam)


        if (adapters.isEmpty()) {
            return createSpec
                .addStatement("return null")
                .build()
        }

        return createSpec
            .addStatement("if (%N.isNotEmpty()) return null", annotationsParam)
            .addCode("\n")
            .addControlFlow("return when (%T.getRawType(%N))", moshiTypes, typeParam) {
                for (adapter in adapters.sortedBy { it.className }) {
                    addCode("«%T::class.java ->\n%T", adapter.targetType, adapter.className)
                    if (adapter.typeVariables.isNotEmpty()) {
                        addCode(adapter.typeVariables.joinToString(", ", prefix = "<", postfix = ">") { "Nothing" })
                    }
                    addCode("(")
                    if (adapter.requiresMoshi) {
                        addCode("%N", moshiParam)
                    }
                    if (adapter.requiresTypes) {
                        if (adapter.requiresMoshi) {
                            addCode(", ")
                        }
                        addCode("%N.%M", typeParam, typeArgumentsOrFail)
                    }
                    addCode(")\n»")
                }
                addStatement("else -> null")
            }
            .build()
    }

    companion object {
        private val typeArgumentsOrFail = KotshiUtils::class.java.member("typeArgumentsOrFail")
    }
}
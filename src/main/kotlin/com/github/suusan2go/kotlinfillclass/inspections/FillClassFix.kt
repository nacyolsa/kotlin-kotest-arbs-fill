package com.github.suusan2go.kotlinfillclass.inspections

import com.github.suusan2go.kotlinfillclass.helper.PutArgumentOnSeparateLineHelper
import com.intellij.codeInsight.template.TemplateBuilderImpl
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.impl.ImaginaryEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.builtins.isFunctionType
import org.jetbrains.kotlin.descriptors.ClassConstructorDescriptor
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.ValueParameterDescriptor
import org.jetbrains.kotlin.idea.core.ShortenReferences
import org.jetbrains.kotlin.idea.imports.importableFqName
import org.jetbrains.kotlin.idea.inspections.findExistingEditor
import org.jetbrains.kotlin.idea.intentions.callExpression
import org.jetbrains.kotlin.idea.util.textRangeIn
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtValueArgumentList
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.getPrevSiblingIgnoringWhitespaceAndComments
import org.jetbrains.kotlin.resolve.calls.components.isVararg
import org.jetbrains.kotlin.resolve.lazy.descriptors.LazyClassDescriptor
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.asSimpleType
import org.jetbrains.kotlin.types.typeUtil.isEnum
import org.jetbrains.kotlin.utils.ifEmpty


open class FillClassFix(
    private val description: String,
    private val withoutDefaultValues: Boolean,
    private val withoutDefaultArguments: Boolean,
    private val withTrailingComma: Boolean,
    private val putArgumentsOnSeparateLines: Boolean,
    private val movePointerToEveryArgument: Boolean,
) : LocalQuickFix {
    override fun getName() = description

    override fun getFamilyName() = name

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val argumentList = descriptor.psiElement as? KtValueArgumentList ?: return
        val call = argumentList.parent as? KtCallElement ?: return
        val descriptors = call.customAnalyze().ifEmpty { return }

        val lambdaArgument = call.lambdaArguments.singleOrNull()
        val editor = argumentList.findExistingEditor()
            ?: ImaginaryEditor(project, argumentList.containingFile.viewProvider.document)
        if (descriptors.size == 1 || editor is ImaginaryEditor) {
            argumentList.fillArgumentsAndFormat(
                descriptor = descriptors.first(),
                editor = editor,
                lambdaArgument = lambdaArgument,
            )
        } else {
            val listPopup = createListPopup(argumentList, lambdaArgument, descriptors, editor)
            JBPopupFactory.getInstance().createListPopup(listPopup).showInBestPositionFor(editor)
        }
    }

    private fun createListPopup(
        argumentList: KtValueArgumentList,
        lambdaArgument: KtLambdaArgument?,
        descriptors: List<FunctionDescriptor>,
        editor: Editor,
    ): BaseListPopupStep<String> {
        val functionName = descriptors.first().let { descriptor ->
            if (descriptor is ClassConstructorDescriptor) {
                descriptor.containingDeclaration.name.asString()
            } else {
                descriptor.name.asString()
            }
        }
        val functions = descriptors
            .sortedBy { descriptor -> descriptor.valueParameters.size }
            .associateBy { descriptor ->
                val key = descriptor.valueParameters.joinToString(
                    separator = ", ",
                    prefix = "$functionName(",
                    postfix = ")",
                    transform = { "${it.name}: ${it.type}" },
                )
                key
            }
        return object : BaseListPopupStep<String>("Choose Function", functions.keys.toList()) {
            override fun isAutoSelectionEnabled() = false

            override fun onChosen(selectedValue: String, finalChoice: Boolean): PopupStep<*>? {
                if (finalChoice) {
                    val parameters = functions[selectedValue]?.valueParameters.orEmpty()
                    CommandProcessor.getInstance().runUndoTransparentAction {
                        runWriteAction {
                            argumentList.fillArgumentsAndFormat(parameters, editor, lambdaArgument)
                        }
                    }
                }
                return PopupStep.FINAL_CHOICE
            }
        }
    }

    private fun KtValueArgumentList.fillArgumentsAndFormat(
        descriptor: FunctionDescriptor,
        editor: Editor,
        lambdaArgument: KtLambdaArgument?,
    ) {
        fillArgumentsAndFormat(descriptor.valueParameters, editor, lambdaArgument)
    }

    private fun KtValueArgumentList.fillArgumentsAndFormat(
        parameters: List<ValueParameterDescriptor>,
        editor: Editor,
        lambdaArgument: KtLambdaArgument? = null,
    ) {
        val argumentSize = arguments.size
        val factory = KtPsiFactory(this.project)
        fillArguments(factory, parameters, lambdaArgument)

        // post-fill process

        // 1. Put arguments on separate lines
        if (putArgumentsOnSeparateLines) {
            PsiDocumentManager.getInstance(project).doPostponedOperationsAndUnblockDocument(editor.document)
            if (this.arguments.isNotEmpty()) {
                PutArgumentOnSeparateLineHelper.applyTo(this, editor)
            }
            findElementsInArgsByType<KtValueArgumentList>(argumentSize)
                .filter { it.arguments.isNotEmpty() }
                .forEach { PutArgumentOnSeparateLineHelper.applyTo(it, editor) }
        }

        // 2. Add trailing commas
        if (withTrailingComma) {
            addTrailingCommaIfNeeded(factory)
            findElementsInArgsByType<KtValueArgumentList>(argumentSize)
                .forEach { it.addTrailingCommaIfNeeded(factory) }
        }

        // 3. Remove full qualifiers and import references
        // This should be run after PutArgumentOnSeparateLineHelper
        findElementsInArgsByType<KtQualifiedExpression>(argumentSize)
            .forEach { ShortenReferences.DEFAULT.process(it) }
        findElementsInArgsByType<KtLambdaExpression>(argumentSize)
            .forEach { ShortenReferences.DEFAULT.process(it) }

        // 4. Set argument placeholders
        // This should be run on final state
        if (editor !is ImaginaryEditor && movePointerToEveryArgument) {
            PsiDocumentManager.getInstance(project).doPostponedOperationsAndUnblockDocument(editor.document)
            startToReplaceArguments(argumentSize, editor)
        }
    }

    private fun KtValueArgumentList.fillArguments(
        factory: KtPsiFactory,
        parameters: List<ValueParameterDescriptor>,
        lambdaArgument: KtLambdaArgument? = null,
    ) {
        val arguments = this.arguments
        val argumentNames = arguments.mapNotNull { it.getArgumentName()?.asName?.identifier }

        val lastIndex = parameters.size - 1
        parameters.forEachIndexed { index, parameter ->
            if (lambdaArgument != null && index == lastIndex && parameter.type.isFunctionType) return@forEachIndexed
            if (arguments.size > index && !arguments[index].isNamed()) return@forEachIndexed
            if (parameter.name.identifier in argumentNames) return@forEachIndexed
            if (parameter.isVararg) return@forEachIndexed
            if (withoutDefaultArguments && parameter.declaresDefaultValue()) return@forEachIndexed
            addArgument(createDefaultValueArgument(parameter, factory))
        }
    }

    private fun createDefaultValueArgument(
        parameter: ValueParameterDescriptor,
        factory: KtPsiFactory,
    ): KtValueArgument {
        if (withoutDefaultValues) {
            return factory.createArgument(null, parameter.name)
        }

        val value = fillValue(parameter)
        return factory.createArgument(factory.createExpressionIfPossible(value ?: ""), parameter.name)
    }

    protected open fun fillValue(descriptor: ValueParameterDescriptor): String? {
        val type = descriptor.type
        return when {
            KotlinBuiltIns.isBoolean(type) -> "boolean()".appendOrNullIfNullable(type)
            KotlinBuiltIns.isChar(type) -> "char()".appendOrNullIfNullable(type)
            KotlinBuiltIns.isDouble(type) -> "positiveDouble()".appendOrNullIfNullable(type)
            KotlinBuiltIns.isFloat(type) -> "positiveFloat()".appendOrNullIfNullable(type)
            KotlinBuiltIns.isInt(type) -> "positiveInt()".appendOrNullIfNullable(type)
            KotlinBuiltIns.isLong(type) -> "positiveLong()".appendOrNullIfNullable(type)
            KotlinBuiltIns.isShort(type) -> "positiveShort()".appendOrNullIfNullable(type)
            KotlinBuiltIns.isCharSequence(type) ||
                    KotlinBuiltIns.isString(type) -> "string()".appendOrNullIfNullable(type)

            // TODO add support for collections
            KotlinBuiltIns.isListOrNullableList(type) -> "listOf()".appendOrNullIfNullable(type)
            KotlinBuiltIns.isSetOrNullableSet(type) -> "setOf()".appendOrNullIfNullable(type)
            KotlinBuiltIns.isMapOrNullableMap(type) -> "mapOf()".appendOrNullIfNullable(type)
            type.isEnum() -> "enum<${type.asSimpleType()}>()".appendOrNullIfNullable(type)
            type.isFunctionType -> "{}"
            // FIXME something doesn't work for nullable types
            else ->             {
                val arbNameForCustomClass = type.asSimpleType().toString().replaceFirstChar { it.lowercaseChar() }
                "$arbNameForCustomClass()".appendOrNullIfNullable(type)
            }
        }.appendBindSuffix()
    }

    private fun String.appendBindSuffix() = "$this.bind()"

    private fun String.appendOrNullIfNullable(type: KotlinType) =
        if (type.isMarkedNullable) "$this.orNull()" else this

    private inline fun <reified T : KtElement> KtValueArgumentList.findElementsInArgsByType(argStartOffset: Int): List<T> {
        return this.arguments.subList(argStartOffset, this.arguments.size).flatMap { argument ->
            argument.collectDescendantsOfType<T>()
        }
    }

    private fun KtValueArgumentList.addTrailingCommaIfNeeded(factory: KtPsiFactory) {
        if (this.arguments.isNotEmpty() && !this.hasTrailingComma()) {
            val comma = factory.createComma()
            this.addAfter(comma, this.arguments.last())
        }
    }

    private fun KtValueArgumentList.hasTrailingComma() =
        rightParenthesis?.getPrevSiblingIgnoringWhitespaceAndComments(withItself = false)?.node?.elementType == KtTokens.COMMA

    private fun KtValueArgumentList.startToReplaceArguments(startIndex: Int, editor: Editor) {
        val templateBuilder = TemplateBuilderImpl(this)
        arguments.drop(startIndex).forEach { argument ->
            val argumentExpression = argument.getArgumentExpression()
            if (argumentExpression != null) {
                templateBuilder.replaceElement(argumentExpression, argumentExpression.text)
            } else {
                val commaOffset = if (argument.text.lastOrNull() == ',') 1 else 0
                val endOffset = argument.textRangeIn(this).endOffset - commaOffset
                templateBuilder.replaceRange(TextRange(endOffset, endOffset), "")
            }
        }
        templateBuilder.run(editor, true)
    }
}

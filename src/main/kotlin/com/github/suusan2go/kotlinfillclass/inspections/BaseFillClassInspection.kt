package com.github.suusan2go.kotlinfillclass.inspections

import com.intellij.codeInspection.ProblemsHolder
import org.jetbrains.kotlin.descriptors.ClassConstructorDescriptor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.idea.codeinsight.api.classic.inspections.AbstractKotlinInspection
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.idea.search.usagesSearch.descriptor
import org.jetbrains.kotlin.load.java.descriptors.JavaCallableMemberDescriptor
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.valueArgumentListVisitor
import org.jetbrains.kotlin.resolve.calls.components.isVararg
import org.jetbrains.kotlin.resolve.calls.util.getResolvedCall
import org.jetbrains.kotlin.resolve.lazy.BodyResolveMode
import org.jetbrains.kotlin.utils.addToStdlib.ifFalse
import org.jetbrains.kotlin.utils.ifEmpty

abstract class BaseFillClassInspection(
    @JvmField var withoutDefaultValues: Boolean = false,
    @JvmField var withoutDefaultArguments: Boolean = false,
    @JvmField var withTrailingComma: Boolean = false,
    @JvmField var putArgumentsOnSeparateLines: Boolean = true,
    @JvmField var movePointerToEveryArgument: Boolean = false,
) : AbstractKotlinInspection() {
    override fun buildVisitor(
        holder: ProblemsHolder,
        isOnTheFly: Boolean,
    ) = valueArgumentListVisitor { element ->
        val callElement = element.parent as? KtCallElement ?: return@valueArgumentListVisitor
        val descriptors = callElement.customAnalyze().ifEmpty { return@valueArgumentListVisitor }
        descriptors.any { descriptor -> descriptor is ClassConstructorDescriptor }.ifFalse { return@valueArgumentListVisitor }
        val description = getConstructorPromptTitle()
        val fix = createFillClassFix(
            description = description,
            withoutDefaultValues = withoutDefaultValues,
            withoutDefaultArguments = withoutDefaultArguments,
            withTrailingComma = withTrailingComma,
            putArgumentsOnSeparateLines = putArgumentsOnSeparateLines,
            movePointerToEveryArgument = movePointerToEveryArgument,
        )
        holder.registerProblem(element, description, fix)
    }

    abstract fun getConstructorPromptTitle(): String

    open fun createFillClassFix(
        description: String,
        withoutDefaultValues: Boolean,
        withoutDefaultArguments: Boolean,
        withTrailingComma: Boolean,
        putArgumentsOnSeparateLines: Boolean,
        movePointerToEveryArgument: Boolean,
    ): FillClassFix = FillClassFix(
        description = description,
        withoutDefaultValues = withoutDefaultValues,
        withoutDefaultArguments = withoutDefaultArguments,
        withTrailingComma = withTrailingComma,
        putArgumentsOnSeparateLines = putArgumentsOnSeparateLines,
        movePointerToEveryArgument = movePointerToEveryArgument,
    )

    companion object {
        const val LABEL_WITHOUT_DEFAULT_VALUES = "Fill arguments without default values"
        const val LABEL_WITHOUT_DEFAULT_ARGUMENTS = "Do not fill default arguments"
        const val LABEL_WITH_TRAILING_COMMA = "Append trailing comma"
        const val LABEL_PUT_ARGUMENTS_ON_SEPARATE_LINES = "Put arguments on separate lines"
        const val LABEL_MOVE_POINTER_TO_EVERY_ARGUMENT = "Move pointer to every argument"
    }
}

fun KtCallElement.customAnalyze(): List<FunctionDescriptor> {
    val context = this.analyze(BodyResolveMode.PARTIAL)
    val resolvedCall = this.calleeExpression?.getResolvedCall(context)
    val descriptors = if (resolvedCall != null) {
        val descriptor = resolvedCall.resultingDescriptor as? FunctionDescriptor ?: return emptyList()
        listOf(descriptor)
    } else {
        this.calleeExpression?.mainReference?.multiResolve(false).orEmpty().mapNotNull {
            val func = it.element as? KtFunction ?: return@mapNotNull null
            val descriptor = func.descriptor as? FunctionDescriptor ?: return@mapNotNull null
            descriptor
        }
    }
    val argumentSize = this.valueArguments.size
    return descriptors.filter { descriptor ->
        descriptor !is JavaCallableMemberDescriptor &&
                descriptor.valueParameters.filterNot { it.isVararg }.size > argumentSize
    }
}

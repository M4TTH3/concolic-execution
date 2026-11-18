package poc.bytecode

import io.github.cvc5.Term

/** One step of a path signature: which site, and which way it went. */
data class Branch(val siteId: Int, val taken: Boolean) {
    fun flipped() = Branch(siteId, !taken)

    override fun toString() = "#$siteId${if (taken) "T" else "F"}"
}

/**
 * A symbolic branch decision recorded during a run. [term] is already oriented toward the direction
 * actually taken: it is the condition when [taken] is true and its negation otherwise.
 */
class PathConstraint(val site: BranchSite, val taken: Boolean, val term: Term) {
    val branch: Branch get() = Branch(site.id, taken)

    override fun toString() = "$branch $term"
}

/** A runtime error at an implicit branch site (division by zero, index out of bounds). */
class PocRuntimeError(val site: BranchSite, message: String) : Exception(message)

/** The run hit the step or call-depth budget (e.g. unbounded recursion). */
class LimitExceeded(message: String) : Exception(message)

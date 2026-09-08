package org.javacs.kt.implementation

import org.jetbrains.kotlin.descriptors.ClassDescriptor

/**
 * Walks the class hierarchy to find all super-classifiers (transitive supertypes).
 * Uses BFS to traverse the type hierarchy and yield all superclasses and interfaces.
 */
fun ClassDescriptor.getAllSuperClassifiers(): Sequence<ClassDescriptor> = sequence {
    val visited = mutableSetOf<ClassDescriptor>()
    val queue = ArrayDeque<ClassDescriptor>()

    for (supertype in this@getAllSuperClassifiers.typeConstructor.supertypes) {
        (supertype.constructor.declarationDescriptor as? ClassDescriptor)?.let { queue.add(it) }
    }

    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        if (!visited.add(current)) continue
        yield(current)
        for (supertype in current.typeConstructor.supertypes) {
            (supertype.constructor.declarationDescriptor as? ClassDescriptor)?.let { queue.add(it) }
        }
    }
}

/**
 * Returns the *direct* supertypes (parents) of a class, de-duplicated. Used by
 * `typeHierarchy/supertypes` so the client can expand the hierarchy one level at
 * a time (LSP-idiomatic), instead of receiving the full flattened transitive set.
 */
fun ClassDescriptor.directSuperClassifiers(): List<ClassDescriptor> {
    val seen = mutableSetOf<ClassDescriptor>()
    return typeConstructor.supertypes.mapNotNull { supertype ->
        supertype.constructor.declarationDescriptor as? ClassDescriptor
    }.distinct().filter { seen.add(it) }
}

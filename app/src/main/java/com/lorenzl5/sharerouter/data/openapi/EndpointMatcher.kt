package com.lorenzl5.sharerouter.data.openapi

import com.lorenzl5.sharerouter.data.SharedContent

/** Selects the endpoints whose accepted kinds overlap the shared content. */
object EndpointMatcher {

    fun match(spec: ParsedSpec, content: SharedContent): List<Endpoint> =
        spec.endpoints
            .filter { ep -> matches(ep, content) }
            .sortedWith(
                compareByDescending<Endpoint> { it.annotated }      // annotated endpoints first
                    .thenBy { it.tags.firstOrNull() ?: "" }
                    .thenBy { it.title.lowercase() }
            )

    private fun matches(ep: Endpoint, content: SharedContent): Boolean {
        if (ep.accepts.intersect(content.kinds).isEmpty()) return false

        // If this endpoint constrains URL hosts and the share is a URL, enforce it.
        val host = content.urlHost
        if (ep.urlHosts != null && host != null && com.lorenzl5.sharerouter.data.InputKind.URL in ep.accepts) {
            return ep.urlHosts.any { allowed ->
                host.equals(allowed, true) || host.endsWith(".$allowed", true)
            }
        }
        return true
    }
}

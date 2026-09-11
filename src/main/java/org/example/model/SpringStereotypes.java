package org.example.model;

import java.util.Set;

/** Annotations that mark a class as Spring-managed, for injection-point detection. */
public final class SpringStereotypes {

    public static final Set<String> STEREOTYPE_ANNOTATIONS = Set.of(
            "Component", "Service", "Repository", "Controller", "RestController", "Configuration"
    );

    private SpringStereotypes() {
    }
}

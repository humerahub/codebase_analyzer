package org.example.model;

/**
 * Every relationship type the dependency graph can contain. Carries its own DOT
 * color, so the exporter doesn't need a separate switch statement to stay in
 * sync with this list.
 */
public enum Layer {
    LAYER1_CALLS("gray55"),
    LAYER2_EXTENDS("darkorange2"),
    LAYER2_IMPLEMENTED_BY("darkorchid"),
    LAYER3_INJECTS("steelblue"),
    LAYER3_RESOLVED("forestgreen"),
    LAYER3_BEAN_PROVIDES("darkgoldenrod"),
    LAYER3_BEAN_RESOLVED("teal"),
    LAYER3_AMBIGUOUS("red");

    public final String dotColor;

    Layer(String dotColor) {
        this.dotColor = dotColor;
    }
}

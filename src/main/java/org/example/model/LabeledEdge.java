package org.example.model;

import org.jgrapht.graph.DefaultEdge;

/**
 * JGraphT's built-in DefaultEdge carries no data of its own, so we subclass it
 * to carry the layer + detail along with every edge in the graph.
 */
public class LabeledEdge extends DefaultEdge {

    public final Layer layer;
    public final String detail;

    public LabeledEdge(Layer layer, String detail) {
        this.layer = layer;
        this.detail = detail;
    }
}

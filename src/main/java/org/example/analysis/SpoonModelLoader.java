package org.example.analysis;

import spoon.Launcher;
import spoon.reflect.declaration.CtType;

import java.util.ArrayList;
import java.util.List;

/** Wraps Spoon's launcher setup so callers just get back the parsed model's types. */
public final class SpoonModelLoader {

    private SpoonModelLoader() {
    }

    public static List<CtType<?>> load(String sourceRoot) {
        Launcher launcher = new Launcher();
        launcher.addInputResource(sourceRoot);
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setComplianceLevel(17);
        launcher.buildModel();
        return new ArrayList<>(launcher.getModel().getAllTypes());
    }
}

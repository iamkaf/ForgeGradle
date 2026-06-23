/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.gradle.internal;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.services.ServiceReference;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.process.ExecOperations;
import org.gradle.work.DisableCachingByDefault;

import javax.inject.Inject;

@DisableCachingByDefault(because = "Mavenizer uses external repositories and shared local caches")
abstract class MavenizerTask extends DefaultTask implements ForgeGradleTask {
    private static final Logger LOGGER = Logging.getLogger(MavenizerTask.class);

    private final ExecOperations execOps;

    @Inject
    public MavenizerTask(ExecOperations execOps) {
        this.execOps = execOps;
    }

    @Classpath
    abstract ConfigurableFileCollection getClasspath();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    abstract RegularFileProperty getJavaLauncher();

    @Input
    abstract Property<String> getMainClass();

    @Input
    abstract ListProperty<String> getArguments();

    @Input
    abstract Property<String> getMaxHeapSize();

    @OutputFile
    abstract RegularFileProperty getOutputJson();

    @OutputFile
    abstract RegularFileProperty getOutputArtifact();

    @ServiceReference("forgeGradleMavenizer")
    abstract Property<MavenizerBuildService> getMavenizerService();

    @TaskAction
    protected void exec() throws InterruptedException {
        var maxAttempts = 3;
        RuntimeException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                execOnce();
                return;
            } catch (RuntimeException e) {
                last = e;
                if (attempt == maxAttempts)
                    break;

                LOGGER.warn("Mavenizer failed on attempt {}/{} for {}. Retrying.", attempt, maxAttempts, getPath(), e);
                Thread.sleep(1_000L * attempt);
            }
        }

        throw last;
    }

    private void execOnce() {
        this.execOps.javaexec(spec -> {
            getMavenizerService().get();
            spec.setClasspath(getClasspath());
            spec.setExecutable(getJavaLauncher().get());
            spec.getMainClass().set(getMainClass());
            spec.setArgs(getArguments().get());
            spec.setMaxHeapSize(getMaxHeapSize().get());

            LOGGER.info("Executing Mavenizer: ");
            var itr = getClasspath().iterator();
            LOGGER.info("  Classpath: {}", itr.next().getAbsolutePath());
            while (itr.hasNext())
                LOGGER.info("             {}", itr.next().getAbsolutePath());

            LOGGER.info("  Java: {}", getJavaLauncher().get().getAsFile().getAbsolutePath());
            var args = getArguments().get();
            var prefix = "  Arguments: ";
            for (int x = 0; x < args.size(); x++) {
                var current = args.get(x);
                var next = args.size() > x + 1 ? args.get(x + 1) : null;
                var line = current;
                if (current.startsWith("--") && next != null && !next.startsWith("--")) {
                    x++;
                    line += ' ' + next;
                }
                LOGGER.info("{}{}", prefix, line);
                prefix = "             ";
            }
        }).rethrowFailure().assertNormalExitValue();
    }
}

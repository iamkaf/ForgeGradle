/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.gradle.internal;

import groovy.json.JsonSlurper;
import net.minecraftforge.gradle.MavenizerInstance;
import org.gradle.api.artifacts.ExternalModuleDependency;
import org.gradle.api.file.RegularFile;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskProvider;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.util.List;
import java.util.Map;

class MavenizerInstanceImpl implements MavenizerInstance {
    private static final Logger LOGGER = Logging.getLogger(MavenizerInstanceImpl.class);
    private final MinecraftExtensionImpl.ForProjectImpl extension;
    private final ForgeGradleProblems problems;
    private final Provider<Boolean> valueSource;
    private final TaskProvider<MavenizerTask> task;
    private final ExternalModuleDependency dependency;
    private final Provider<RegularFile> jsonFile;

    private final MapProperty<String, String> invoke;
    private @Nullable Map<String, String> map;

    MavenizerInstanceImpl(
        MinecraftExtensionImpl.ForProjectImpl extension,
        Provider<Boolean> valueSource,
        TaskProvider<MavenizerTask> task,
        ExternalModuleDependency dependency,
        Provider<RegularFile> jsonFile
    ) {
        this.extension = extension;
        this.problems = this.extension.getObjects().newInstance(ForgeGradleProblems.class);
        this.dependency = dependency;
        this.valueSource = valueSource;
        this.task = task;
        this.jsonFile = jsonFile;
        this.invoke = this.extension.getObjects().mapProperty(String.class, String.class)
            .convention(this.extension.getProviders().provider(this::invoke));
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> invoke() {
        if (this.map == null) {
            var file = this.jsonFile.get().getAsFile();
            if (!file.isFile())
                valueSource.get(); // Fallback for direct provider reads and auto mappings.
            this.map = validate((Map<String, String>) new JsonSlurper().parse(file, "UTF-8"));
            //this.map.forEach((k, v) -> this.extension.getProject().getLogger().lifecycle(k + " => " + v));
        }
        return this.map;
    }

    private Map<String, String> validate(Map<String, String> map) {
        // this entire error check is gated behind a gradle property. if it's set to false, stop immediately.
        // also don't bother checking if we aren't using Forge, which is net.minecraftforge:forge/fmlonly
        // this code is kind of ugly but I don't know how to make it any cleaner without the nesting.
        if (!problems.testFalse("net.minecraftforge.gradle.warnings.minecraft.legacy.renamer.missing")) {
            var minecraftVersion = map.get("mc.version");
            boolean forge = "net.minecraftforge".equals(dependency.getGroup())
                && ("forge".equals(dependency.getName()) || "fmlonly".equals(dependency.getName()));
            if (minecraftVersion != null && !"UNKNOWN".equals(minecraftVersion)) {
                boolean legacy = false;
                try {
                    if (minecraftVersion.indexOf('w') > 0) {
                        var split = minecraftVersion.split("[w|a-z]");
                        int int1 = Integer.parseInt(split[0]);
                        int int2 = Integer.parseInt(split[1]);
                        legacy = int1 <= 24 && int2 <= 13; // 24w13a was the last snapshot before 1.20.5
                    } else {
                        var split = minecraftVersion.split("[.|-]");
                        int int1 = Integer.parseInt(split[0]);
                        int int2 = Integer.parseInt(split[1]);
                        int int3 = split.length > 2 ? Integer.parseInt(split[2]) : 0;
                        legacy = forge ? int1 <= 1 && int2 <= 20 && int3 < 5
                            : int1 < 26; // version < 1.20.5 == legacy for forge, 26 for vanilla
                    }
                } catch (Exception e) {
                    // there are a number of things that could go wrong here.
                    // but it should never cause a build failure. stop immediately and report problem.
                    problems.reportMissingRenamerCheckFailed(dependency, e);
                }

                if (legacy && !extension.getProject().getPluginManager().hasPlugin("net.minecraftforge.renamer")) {
                    problems.reportMissingRenamerPluginForOldVersion(dependency);
                }
            }
        }

        return map;
    }

    private Provider<String> get(String key) {
        return this.invoke.getting(key)
            .orElse(this.extension.getProviders().provider(() -> {
                throw new IllegalStateException("Mavenizer did not output expected json data " + key);
            }));
    }

    void ensureGenerated() {
        synchronized (this) {
            var file = this.jsonFile.get().getAsFile();
            if (!file.isFile())
                valueSource.get();
        }
    }

    private Provider<String> get(String key, @Nullable String _default, String requiredVersion) {
        return this.invoke.getting(key)
            .orElse(this.extension.getProviders().provider(() -> {
                // This should only happen when someone hardcodes their tool version, warn them
                var version = this.extension.plugin.getTool(Tools.MAVENIZER).getModule();
                var message = "Mavenizer did not output expected json data " + key + ", Make sure you're using Mavenizer >= " + requiredVersion + " using " + version;
                if (_default != null) {
                    LOGGER.warn(message);
                    return _default;
                }
                throw new IllegalStateException(message);
            }));
    }

    @Override
    public Provider<ExternalModuleDependency> asProvider() {
        // The dependency notation is already known; Mavenizer-backed metadata providers trigger generation during resolution.
        return this.extension.getProviders().provider(() -> this.dependency);
    }

    TaskProvider<MavenizerTask> getTaskProvider() {
        return this.task;
    }

    @Override
    public Provider<String> getMappingChannel() {
        return get("mappings.channel");
    }

    @Override
    public Provider<String> getMappingVersion() {
        return get("mappings.version");
    }

    @Override
    public Provider<String> getToSrg() {
        return get("mappings.srg.artifact");
    }

    @Override
    public Provider<File> getToSrgFile() {
        return get("mappings.srg.file").map(this.extension.getProject()::file);
    }

    Provider<File> getToSrgFileWhenObfuscated() {
        return getWhenObfuscated(getToSrgFile());
    }

    @Override
    public Provider<String> getToObf() {
        return get("mappings.obf.artifact");
    }

    @Override
    public Provider<File> getToObfFile() {
        return get("mappings.obf.file").map(this.extension.getProject()::file);
    }

    Provider<File> getToObfFileWhenObfuscated() {
        return getWhenObfuscated(getToObfFile());
    }

    @Override
    public Provider<String> getMinecraftVersion() {
        return get("mc.version", "UNKNOWN", "0.4.33");
    }

    @Override
    public Provider<String> getMCPVersion() {
        return get("mcp.version", "UNKNOWN", "0.4.33");
    }

    private Provider<File> getWhenObfuscated(Provider<File> provider) {
        return getMinecraftVersion().flatMap(version -> Util.isObfuscated(version)
            ? provider
            : this.extension.getProviders().provider(() -> (File)null));
    }

    // Internal, not sure if I want to return
    public Provider<List<String>> getPatcherModules() {
        return get("patcher.modules", "", "0.4.33")
            .map(value -> value.isBlank() ? List.of() : List.of(value.split(",")));
    }
}

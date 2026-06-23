/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.gradle.internal;

import org.gradle.api.services.BuildService;
import org.gradle.api.services.BuildServiceParameters;

import javax.inject.Inject;

abstract class MavenizerBuildService implements BuildService<BuildServiceParameters.None> {
    @Inject
    public MavenizerBuildService() {
    }
}

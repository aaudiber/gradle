/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal.filewatch;

import org.gradle.api.Task;
import org.gradle.api.internal.file.FileSystemSubset;
import org.gradle.api.internal.file.WatchPointsBuilder;

public class DefaultWatchPointsRegistry implements WatchPointsRegistry {
    private final FileSystemSubset.Builder builder = FileSystemSubset.builder();

    public DefaultWatchPointsRegistry() {

    }

    @Override
    public WatchPointsBuilder createForTask(Task task) {
        return builder;
    }

    public FileSystemSubset buildFileSystemSubset() {
        return builder.build();
    }
}
/*
 * Copyright (c) 2015 CoreOS Inc.
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.coreos.appc.docker;

import static com.google.common.base.CharMatcher.WHITESPACE;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.Collections;
import java.util.List;

import com.coreos.appc.ContainerBuilder;
import com.coreos.appc.ContainerFile;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.spotify.docker.client.AnsiProgressHandler;
import com.spotify.docker.client.DockerClient;

public class DockerContainerBuilder extends ContainerBuilder {
  final DockerClient docker;
  final String dockerBuildDir;
  final List<String> addFiles = Lists.newArrayList();

  /**
   * Set to override the auto-generated dockerfile
   */
  public Path dockerfile;

  public DockerContainerBuilder(DockerClient docker, String dockerBuildDir) {
    this.docker = docker;
    this.dockerBuildDir = dockerBuildDir;
  }

  private void createDockerFile() throws IOException {
    final List<String> commands = Lists.newArrayList();
    if (baseImage != null) {
      commands.add("FROM " + baseImage);
    }
    if (maintainer != null) {
      commands.add("MAINTAINER " + maintainer);
    }
    if (entryPoint != null) {
      commands.add("ENTRYPOINT " + entryPoint);
    }
    if (cmd != null) {
      // TODO(dano): we actually need to check whether the base image has an entrypoint
      if (entryPoint != null) {
        // CMD needs to be a list of arguments if ENTRYPOINT is set.
        if (cmd.startsWith("[") && cmd.endsWith("]")) {
          // cmd seems to be an argument list, so we're good
          commands.add("CMD " + cmd);
        } else {
          // cmd does not seem to be an argument list, so try to generate one.
          final List<String> args = ImmutableList.copyOf(
              Splitter.on(WHITESPACE).omitEmptyStrings().split(cmd));
          final StringBuilder cmdBuilder = new StringBuilder("[");
          for (String arg : args) {
            cmdBuilder.append('"').append(arg).append('"');
          }
          cmdBuilder.append(']');
          final String cmdString = cmdBuilder.toString();
          commands.add("CMD " + cmdString);
          log.warn("Entrypoint provided but cmd is not an explicit list. Attempting to "
              + "generate CMD string in the form of an argument list.");
          log.warn("CMD " + cmdString);
        }
      } else {
        // no ENTRYPOINT set so use cmd verbatim
        commands.add("CMD " + cmd);
      }
    } else {
      commands.add("CMD []");
    }

    for (String file : addFiles) {
      commands.add(String.format("ADD %s %s", file, file));
    }

    if (env != null) {
      final List<String> sortedKeys = Ordering.natural().sortedCopy(env.keySet());
      for (String key : sortedKeys) {
        final String value = env.get(key);
        commands.add(String.format("ENV %s %s", key, value));
      }
    }

    if (exposesSet != null && exposesSet.size() > 0) {
      // The values will be sorted with no duplicated since exposesSet is a TreeSet
      commands.add("EXPOSE " + Joiner.on(" ").join(exposesSet));
    }

    // this will overwrite an existing file
    Files.createDirectories(Paths.get(dockerBuildDir));
    Files.write(Paths.get(dockerBuildDir, "Dockerfile"), commands, UTF_8);
  }

  @Override
  public void addFiles(List<ContainerFile> containerFiles) throws IOException {
    List<String> copiedPaths = Lists.newArrayList();

    for (ContainerFile containerFile : containerFiles) {
      final Path sourcePath = containerFile.sourcePath;

      final Path destPath = Paths.get(dockerBuildDir, containerFile.imagePath);
      log.info(String.format("Copying %s -> %s", sourcePath, destPath));
      // ensure all directories exist because copy operation will fail if they don't
      Files.createDirectories(destPath.getParent());
      Files.copy(sourcePath, destPath, StandardCopyOption.REPLACE_EXISTING);
      Files.setLastModifiedTime(destPath, FileTime.fromMillis(0));
      // file location relative to docker directory, used later to generate Dockerfile
      final Path relativePath = Paths.get(containerFile.imagePath);
      copiedPaths.add(relativePath.toString());
    }

    // The list of included files returned from DirectoryScanner can be in a different order
    // each time. This causes the ADD statements in the generated Dockerfile to appear in a
    // different order. We want to avoid this so each run of the plugin always generates the same
    // Dockerfile, which also makes testing easier. Sort the list of paths for each resource
    // before adding it to the allCopiedPaths list. This way we follow the ordering of the
    // resources in the pom, while making sure all the paths of each resource are always in the
    // same order.
    Collections.sort(copiedPaths);
    addFiles.addAll(copiedPaths);
  }

  public void buildImage(String imageName, String imageVersion) throws Exception {
    if (dockerfile == null) {
      createDockerFile();
    }

    log.info("Building image " + imageName);
    docker.build(Paths.get(dockerBuildDir), imageName, new AnsiProgressHandler());
    log.info("Built " + imageName);
  }

}

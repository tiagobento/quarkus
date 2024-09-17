package io.quarkus.bootstrap.resolver.maven;

import java.nio.file.Path;

import org.eclipse.aether.repository.LocalRepositoryManager;

public interface LocalRepositoryManagerWithRelink extends LocalRepositoryManager {

    public void relink(String groupId, String artifactId, String classifier, String type, String version, Path p);
}

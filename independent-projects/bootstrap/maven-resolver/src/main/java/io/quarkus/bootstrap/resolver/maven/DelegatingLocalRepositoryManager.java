package io.quarkus.bootstrap.resolver.maven;

import java.io.IOException;
import java.nio.file.Path;

import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.metadata.Metadata;
import org.eclipse.aether.repository.*;

import io.quarkus.bootstrap.util.IoUtils;

/**
 *
 * @author Alexey Loubyansky
 */
public class DelegatingLocalRepositoryManager implements LocalRepositoryManagerWithRelink {

    private final LocalRepositoryManager delegate;

    public DelegatingLocalRepositoryManager(LocalRepositoryManager delegate) {
        this.delegate = delegate;
    }

    @Override
    public LocalRepository getRepository() {
        return delegate.getRepository();
    }

    @Override
    public String getPathForLocalArtifact(Artifact artifact) {
        return delegate.getPathForLocalArtifact(artifact);
    }

    @Override
    public String getPathForRemoteArtifact(Artifact artifact, RemoteRepository repository, String context) {
        return delegate.getPathForRemoteArtifact(artifact, repository, context);
    }

    @Override
    public String getPathForLocalMetadata(Metadata metadata) {
        return delegate.getPathForLocalMetadata(metadata);
    }

    @Override
    public String getPathForRemoteMetadata(Metadata metadata, RemoteRepository repository, String context) {
        return delegate.getPathForRemoteMetadata(metadata, repository, context);
    }

    @Override
    public void relink(String groupId, String artifactId, String classifier, String type, String version, Path p) {
        final Path creatorRepoPath = getLocalPath(Path.of(delegate.getRepository().getBasedir().getPath()), groupId, artifactId,
                classifier, type, version);
        try {
            IoUtils.copy(p, creatorRepoPath);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to copy " + p + " to a staging repo", e);
        }
    }

    @Override
    public LocalArtifactResult find(RepositorySystemSession session, LocalArtifactRequest request) {
        return delegate.find(session, request);
    }

    @Override
    public void add(RepositorySystemSession session, LocalArtifactRegistration request) {
        delegate.add(session, request);
    }

    @Override
    public LocalMetadataResult find(RepositorySystemSession session, LocalMetadataRequest request) {
        return delegate.find(session, request);
    }

    @Override
    public void add(RepositorySystemSession session, LocalMetadataRegistration request) {
        delegate.add(session, request);
    }

    private Path getLocalPath(Path repoHome, String groupId, String artifactId, String classifier, String type,
            String version) {
        Path p = repoHome;
        final String[] groupParts = groupId.split("\\.");
        for (String part : groupParts) {
            p = p.resolve(part);
        }
        final StringBuilder fileName = new StringBuilder();
        fileName.append(artifactId).append('-').append(version);
        if (classifier != null && !classifier.isEmpty()) {
            fileName.append('-').append(classifier);
        }
        fileName.append('.').append(type);
        return p.resolve(artifactId).resolve(version).resolve(fileName.toString());
    }
}

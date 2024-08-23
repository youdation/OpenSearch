/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.gradle

import groovy.json.JsonSlurper
import org.ajoberstar.grgit.Grgit
import org.ajoberstar.grgit.operation.BranchListOp
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.internal.os.OperatingSystem

import java.nio.file.Paths

class CheckCompatibilityTask extends DefaultTask {

    static final String REPO_URL = 'https://raw.githubusercontent.com/opensearch-project/opensearch-plugins/main/plugins/.meta'

    @Input
    List repositoryUrls = project.hasProperty('repositoryUrls') ? project.property('repositoryUrls').split(',') : getRepoUrls()

    @Input
    String ref = project.hasProperty('ref') ? project.property('ref') : 'main'

    @Internal
    List failedComponents = []

    @Internal
    List gitFailedComponents = []

    @Internal
    List compatibleComponents = []

    @TaskAction
    void checkCompatibility() {
        repositoryUrls.parallelStream().forEach { repositoryUrl ->
            logger.lifecycle("Checking compatibility for: $repositoryUrl with ref: $ref")
            def tempDir = File.createTempDir()
            def stdout = new ByteArrayOutputStream()
            def errout = new ByteArrayOutputStream()
            def skipped = false;
            try {
                if (cloneAndCheckout(repositoryUrl, tempDir)) {
                    if (repositoryUrl.toString().endsWithAny('notifications', 'notifications.git')) {
                        tempDir = Paths.get(tempDir.getAbsolutePath(), 'notifications')
                    }
                    project.exec {
                        workingDir = tempDir
                        executable = (OperatingSystem.current().isWindows()) ? 'gradlew.bat' : './gradlew'
                        args ('assemble')
                        standardOutput stdout
                        errorOutput errout
                    }
                    compatibleComponents.add(repositoryUrl)
                } else {
                    skipped = true
                }
            } catch (ex) {
                failedComponents.add(repositoryUrl)
                logger.info("Gradle assemble failed for $repositoryUrl", ex)
            } finally {
                if (skipped) {
                    logger.lifecycle("Skipping compatibility check for $repositoryUrl")
                } else {
                    logger.lifecycle("Finished compatibility check for $repositoryUrl")
                    logger.info("Standard output for $repositoryUrl build:\n\n" + stdout.toString())
                    logger.error("Error output for $repositoryUrl build:\n\n" + errout.toString())
                }
                tempDir.deleteDir()
            }
        }
        if (!failedComponents.isEmpty()) {
            logger.lifecycle("Incompatible components: $failedComponents")
        }
        if (!gitFailedComponents.isEmpty()) {
            logger.lifecycle("Components skipped due to git failures: $gitFailedComponents")
        }
        if (!compatibleComponents.isEmpty()) {
            logger.lifecycle("Compatible components: $compatibleComponents")
        }
    }

    protected static List getRepoUrls() {
        def jsonText ='''
            {
              "projects": {
                "common-utils": "git@github.com:opensearch-project/common-utils.git",
                "alerting": "git@github.com:opensearch-project/alerting.git",
                "anomaly-detection": "git@github.com:opensearch-project/anomaly-detection.git",
                "asynchronous-search": "git@github.com:opensearch-project/asynchronous-search.git",
                "cross-cluster-replication": "git@github.com:opensearch-project/cross-cluster-replication.git",
                "custom-codecs": "git@github.com:opensearch-project/custom-codecs.git",
                "flow-framework": "git@github.com:opensearch-project/flow-framework.git",
                "geospatial": "git@github.com:opensearch-project/geospatial.git",
                "index-management": "git@github.com:opensearch-project/index-management.git",
                "job-scheduler": "git@github.com:opensearch-project/job-scheduler.git",
                "k-NN": "git@github.com:opensearch-project/k-NN.git",
                "ml-commons": "git@github.com:opensearch-project/ml-commons.git",
                "neural-search": "git@github.com:opensearch-project/neural-search.git",
                "notifications": "git@github.com:opensearch-project/notifications.git",
                "observability": "git@github.com:opensearch-project/observability.git",
                "opensearch-oci-object-storage": "git@github.com:opensearch-project/opensearch-oci-object-storage.git",
                "reporting": "git@github.com:opensearch-project/reporting.git",
                "performance-analyzer": "git@github.com:opensearch-project/performance-analyzer.git",
                "security-analytics": "git@github.com:opensearch-project/security-analytics.git",
                "security": "git@github.com:opensearch-project/security.git",
                "skills": "git@github.com:opensearch-project/skills.git",
                "sql": "git@github.com:opensearch-project/sql.git"
              }
            }
            '''
        def json = new JsonSlurper().parseText(jsonText)
        def repository = json.projects.values()
        def repoUrls = replaceSshWithHttps(repository as List)
        return repoUrls
    }

    protected static replaceSshWithHttps(List<String> repoList) {
        repoList.replaceAll { element ->
            element.replace("git@github.com:", "https://github.com/")
        }
        return repoList
    }

    protected boolean cloneAndCheckout(repoUrl, directory) {
        try {
            def grgit = Grgit.clone(dir: directory, uri: repoUrl)
            def remoteBranches = grgit.branch.list(mode: BranchListOp.Mode.REMOTE)
            String targetBranch = 'origin/' + ref
            if (remoteBranches.find { it.name == targetBranch } == null) {
                gitFailedComponents.add(repoUrl)
                logger.info("$ref does not exist for $repoUrl. Skipping the compatibility check!!")
                return false
            } else {
                logger.info("Checking out $targetBranch")
                grgit.checkout(branch: targetBranch)
                return true
            }
        } catch (ex) {
            logger.error('Exception occurred during GitHub operations', ex)
            gitFailedComponents.add(repoUrl)
            return false
        }
    }
}

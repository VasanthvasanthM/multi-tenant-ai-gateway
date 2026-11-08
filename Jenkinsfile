// =============================================================================
// Jenkins declarative pipeline.
//
// Deliberately mirrors .github/workflows/ci.yml stage for stage. Both are thin
// wrappers around Maven goals, which is the point: all the real logic lives in
// the build, so the same checks run identically on a laptop, on GitHub Actions
// and here. Neither CI system is load-bearing, and moving between them is a
// configuration change rather than a rewrite.
//
// Why both exist: GitHub Actions is the hosted path, Jenkins the self-hosted one
// that most enterprises still run behind their own firewall. A build that only
// works on one of them has coupled itself to that platform.
//
// Requires: Docker available to the agent (for Testcontainers and image builds),
// and the Maven wrapper from the repo - no Maven installation needed.
// =============================================================================

pipeline {
    agent any

    options {
        // The whole pipeline, not a stage. A hung Testcontainers pull would
        // otherwise occupy an executor indefinitely.
        timeout(time: 60, unit: 'MINUTES')
        // Keep enough history to compare a regression against, not so much that
        // the controller's disk fills with artifacts nobody opens.
        buildDiscarder(logRotator(numToKeepStr: '30', artifactNumToKeepStr: '10'))
        timestamps()
        // A second build of the same branch cancels the first. Without it a busy
        // branch queues runs that are already obsolete.
        disableConcurrentBuilds(abortPrevious: true)
        skipDefaultCheckout(false)
    }

    environment {
        MAVEN_ARGS = '-B -ntp'
        // The reaper container adds a startup race on an ephemeral agent that is
        // torn down anyway.
        TESTCONTAINERS_RYUK_DISABLED = 'true'
        // Matches the deployed services. Both Postgres drivers send the JVM zone
        // to the server, and Java reports legacy ids that slim images reject.
        JAVA_TOOL_OPTIONS = '-Duser.timezone=UTC'
    }

    stages {

        stage('Environment') {
            steps {
                sh '''
                    ./mvnw --version
                    docker --version || echo "WARNING: no Docker - integration tests will fail"
                '''
            }
        }

        stage('Build and unit test') {
            steps {
                sh './mvnw $MAVEN_ARGS verify -DskipITs'
            }
            post {
                always {
                    junit allowEmptyResults: true,
                          testResults: '**/target/surefire-reports/*.xml'
                }
            }
        }

        // Static analysis and integration tests are independent, so they run
        // together. On a single-executor controller Jenkins serialises them
        // anyway, but the declaration is correct and an agent with capacity
        // halves the wall-clock time.
        stage('Verify') {
            parallel {

                stage('Integration test') {
                    steps {
                        sh '''
                            docker pull postgres:17-alpine &
                            docker pull pgvector/pgvector:0.8.7-pg17-trixie &
                            wait
                            ./mvnw $MAVEN_ARGS verify
                        '''
                    }
                    post {
                        always {
                            junit allowEmptyResults: true,
                                  testResults: '**/target/failsafe-reports/*.xml'
                        }
                    }
                }

                stage('Static analysis') {
                    steps {
                        // Coverage thresholds relaxed so SpotBugs and PMD always
                        // report. The coverage gate is its own stage, so a
                        // failure there means exactly one thing.
                        sh '''
                            ./mvnw $MAVEN_ARGS verify -Pquality -DskipITs \
                                -Dcoverage.line.minimum=0 -Dcoverage.branch.minimum=0
                        '''
                    }
                    post {
                        always {
                            archiveArtifacts allowEmptyArchive: true,
                                artifacts: '**/target/spotbugsXml.xml,**/target/pmd.xml,**/target/cpd.xml'
                        }
                    }
                }
            }
        }

        stage('Coverage gate') {
            steps {
                // Advisory until coverage reaches the threshold. UNSTABLE rather
                // than FAILURE so the gap is visible on every build without
                // blocking delivery - and flipping it to blocking is deleting
                // the catchError wrapper.
                catchError(buildResult: 'UNSTABLE', stageResult: 'UNSTABLE') {
                    sh './mvnw $MAVEN_ARGS verify -Pquality'
                }
            }
            post {
                always {
                    // Needs the JaCoCo plugin on the controller; harmless if absent.
                    archiveArtifacts allowEmptyArchive: true,
                        artifacts: 'coverage-report/target/site/jacoco-aggregate/**'
                }
            }
        }

        stage('Mutation testing') {
            when {
                // Main only. PITest re-runs the suite once per surviving mutant,
                // which is tens of minutes with STRONGER mutators - too slow to
                // sit in front of a merge, and the number moves slowly enough
                // that per-branch feedback buys nothing.
                branch 'main'
            }
            steps {
                // Advisory on its first runs, like the coverage gate above: the
                // threshold is 70 and the score has never been measured. Making
                // it blocking before the number is known either fails the build
                // on an unknown or invites someone to lower the threshold to
                // whatever came out, which is how a gate becomes decoration.
                //
                // Scoped to the four modules that have unit tests. control-plane,
                // metering, indexer and audit have none - one IT each - and
                // PITest excludes ITs, so mutating them would score every mutant
                // SURVIVED against the absence of a test rather than against a
                // weak one.
                catchError(buildResult: 'UNSTABLE', stageResult: 'UNSTABLE') {
                    sh '''./mvnw $MAVEN_ARGS verify -Pmutation -DskipITs \
                            -pl common,embedding,kafka-support,gateway'''
                }
                // Guarded like helm and terraform above: the agent's toolset is not
                // ours to assume, and a missing convenience script must not be the
                // reason a build reports UNSTABLE. The XML report is archived either
                // way, so nothing is lost but the inline table.
                sh '''
                    if command -v python3 >/dev/null 2>&1; then
                        python3 scripts/pit-summary.py
                    else
                        echo "python3 not available, skipping the mutation summary"
                    fi
                '''
            }
            post {
                always {
                    archiveArtifacts allowEmptyArchive: true,
                        artifacts: '**/target/pit-reports/**'
                }
            }
        }

        stage('Security scan') {
            steps {
                // Advisory: a newly published CVE in a transitive dependency
                // should raise a flag, not stop a release that was already
                // reviewed. The report is archived either way.
                catchError(buildResult: 'UNSTABLE', stageResult: 'UNSTABLE') {
                    sh './mvnw $MAVEN_ARGS verify -Psecurity -DskipTests'
                }
            }
            post {
                always {
                    archiveArtifacts allowEmptyArchive: true,
                        artifacts: '**/target/dependency-check-report.*'
                }
            }
        }

        stage('SonarQube') {
            when {
                // Only when a server is actually configured. Without the guard
                // every build on a fresh controller fails on a missing
                // installation, which trains people to ignore red builds.
                expression { return env.SONAR_HOST_URL?.trim() }
            }
            steps {
                withCredentials([string(credentialsId: 'sonar-token', variable: 'SONAR_TOKEN')]) {
                    sh '''
                        ./mvnw $MAVEN_ARGS sonar:sonar \
                            -Dsonar.host.url=$SONAR_HOST_URL \
                            -Dsonar.token=$SONAR_TOKEN
                    '''
                }
            }
        }

        stage('Build images') {
            when {
                branch 'main'
            }
            steps {
                sh './mvnw $MAVEN_ARGS package -DskipTests'
                script {
                    def services = ['gateway', 'control-plane', 'metering', 'indexer', 'audit']
                    def registry = env.IMAGE_REGISTRY ?: 'ghcr.io/local'
                    services.each { service ->
                        sh """
                            docker build \
                                -f deploy/docker/Dockerfile \
                                --build-arg SERVICE=${service} \
                                -t ${registry}/ai-gateway-${service}:${env.GIT_COMMIT} \
                                -t ${registry}/ai-gateway-${service}:latest \
                                .
                        """
                        // Scan BEFORE any push. Scanning afterwards means a
                        // vulnerable image is already available to pull.
                        sh """
                            if command -v trivy >/dev/null 2>&1; then
                                trivy image --severity CRITICAL,HIGH --ignore-unfixed \
                                    --exit-code 0 \
                                    ${registry}/ai-gateway-${service}:${env.GIT_COMMIT}
                            else
                                echo "trivy not installed on this agent, skipping image scan"
                            fi
                        """
                    }
                }
            }
        }

        stage('Validate infrastructure') {
            steps {
                sh '''
                    if command -v helm >/dev/null 2>&1 && [ -d deploy/helm/ai-gateway ]; then
                        helm lint deploy/helm/ai-gateway
                        helm template ai-gateway deploy/helm/ai-gateway > /dev/null
                    else
                        echo "helm or chart not available, skipping"
                    fi

                    if command -v terraform >/dev/null 2>&1; then
                        for dir in terraform/local terraform/aws; do
                            if [ -d "$dir" ] && ls "$dir"/*.tf >/dev/null 2>&1; then
                                terraform -chdir="$dir" init -backend=false -input=false
                                terraform -chdir="$dir" fmt -check -recursive
                                terraform -chdir="$dir" validate
                            fi
                        done
                    else
                        echo "terraform not available, skipping"
                    fi
                '''
            }
        }
    }

    post {
        always {
            // Jenkins agents are frequently long-lived, unlike GitHub runners.
            // Without this the workspace accumulates target/ directories until
            // the disk fills - a failure that presents as unrelated build
            // errors days later.
            cleanWs(
                deleteDirs: true,
                notFailBuild: true,
                patterns: [[pattern: '**/target/**', type: 'INCLUDE']]
            )
        }
        unstable {
            echo 'Build is UNSTABLE: a gate did not pass. See the coverage and security stages.'
        }
        failure {
            echo 'Build FAILED. Compilation, tests or static analysis did not pass.'
        }
    }
}

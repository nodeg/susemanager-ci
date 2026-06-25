def run(params) {
    timestamps {
        // Capybara configuration
        def capybara_timeout = 60
        def default_timeout = 500

        env.controller_hostname = null
        env.resultdir = "${WORKSPACE}/results"
        env.resultdirbuild = "${resultdir}/${BUILD_NUMBER}"
        def junit_resultdir = "results/${BUILD_NUMBER}/results_junit"
        env.exports = "export BUILD_NUMBER=${BUILD_NUMBER}; export CAPYBARA_TIMEOUT=${capybara_timeout}; export DEFAULT_TIMEOUT=${default_timeout}; export CUCUMBER_PUBLISH_QUIET=true;"
        env.common_params = "--outputdir ${resultdir} --tf ${params.tf_file} --gitfolder ${resultdir}/sumaform --terraform-bin ${params.bin_path}"

        if (params.deploy_parallelism) {
            env.common_params = "${env.common_params} --parallelism ${params.deploy_parallelism}"
        }

        // Environment variables for migration scripts
        env.REGISTRY_HOST = params.registry_host ?: ''
        env.HOST_OS = params.host_os ?: 'sles'

        def isNewJenkins = env.JENKINS_URL?.contains('jenkins.mgr.suse.de')
        def credInit = isNewJenkins
            ? 'set +x; credFile=$(mktemp); echo "$SECRET_CONTENT" > "${credFile}"; chmod 600 "${credFile}"; . "${credFile}"; rm -f "${credFile}"; set -x'
            : 'set +x; . /home/jenkins/.credentials; set -x'
        def withCreds = { Closure body ->
            if (isNewJenkins) {
                withCredentials([string(credentialsId: 'sumaform-secrets', variable: 'SECRET_CONTENT')]) { body() }
            } else {
                body()
            }
        }

        try {
            stage('Clone terracumber, susemanager-ci') {
                sh "mkdir -p ${resultdir}"
                git url: params.terracumber_gitrepo, branch: params.terracumber_ref
                dir("susemanager-ci") {
                    checkout scm
                }
            }

            stage('Deploy') {
                if (params.use_previous_terraform_state && currentBuild.previousBuild != null) {
                    copyArtifacts(
                        projectName: env.JOB_NAME,
                        selector: [$class: 'SpecificBuildSelector', buildNumber: "${currentBuild.previousBuild.number}"],
                        optional: true
                    )
                }

                if (params.must_deploy) {
                    withCreds {
                        sh """
                            #!/bin/bash
                            set -e -o pipefail
                            ${credInit}
                            rm -rf ${resultdir}/sumaform
                            mkdir -p ${resultdir}/sumaform
                            cp -r terracumber_config ${resultdir}/sumaform/
                            git clone --depth 1 ${params.sumaform_gitrepo} -b ${params.sumaform_ref} ${resultdir}/sumaform/sumaform
                        """

                        if (params.terraform_init) {
                            sh "${params.bin_path} -chdir=${resultdir}/sumaform init ${params.bin_plugins_path}"
                        }

                        if (params.terraform_taint) {
                            sh """
                                #!/bin/bash
                                ${params.bin_path} -chdir=${resultdir}/sumaform taint module.server || true
                                ${params.bin_path} -chdir=${resultdir}/sumaform taint module.proxy || true
                            """
                        }

                        sh """
                            #!/bin/bash
                            set -e -o pipefail
                            ${credInit}
                            export TF_VAR_HOST_OS='${params.host_os}'
                            export TF_VAR_PRERELEASE_REPO_URL='${params.prerelease_repo_url}'
                            export TF_VAR_REGISTRY_HOST='${params.registry_host}'
                            cd ${resultdir}/sumaform
                            ${params.bin_path} apply -auto-approve -var-file=${params.tf_file}
                        """
                    }

                    // Get controller hostname
                    env.controller_hostname = sh(
                        script: "${params.bin_path} -chdir=${resultdir}/sumaform output -raw controller_hostname || echo ''",
                        returnStdout: true
                    ).trim()
                }
            }

            stage('Run Migration Tests') {
                if (params.must_run_migration_tests && params.must_deploy) {
                    sh """
                        #!/bin/bash
                        set -e
                        ${env.exports}
                        export SCC_USER='\${SCC_USER}'
                        export SCC_PASSWORD='\${SCC_PASSWORD}'
                        export REGISTRY_HOST='${params.registry_host}'

                        ssh root@${env.controller_hostname} 'cd /root/spacewalk/testsuite && \
                            cucumber features/migration/server_50_to_51_${params.host_os}.feature && \
                            cucumber features/migration/proxy_50_to_51_${params.host_os}.feature'
                    """
                }
            }

            stage('Collect Results') {
                sh "mkdir -p ${junit_resultdir}"
                sh """
                    scp root@${env.controller_hostname}:/root/spacewalk/testsuite/output*.html ${resultdir}/ || true
                    scp root@${env.controller_hostname}:/root/spacewalk/testsuite/results_junit/*.xml ${junit_resultdir}/ || true
                """

                junit allowEmptyResults: true, testResults: "${junit_resultdir}/*.xml"
                publishHTML(target: [
                    allowMissing: true,
                    alwaysLinkToLastBuild: false,
                    keepAll: true,
                    reportDir: "${resultdir}",
                    reportFiles: 'output*.html',
                    reportName: 'Cucumber HTML Report'
                ])
            }

        } catch (Exception ex) {
            echo "ERROR: ${ex}"
            currentBuild.result = 'FAILURE'
            throw ex
        } finally {
            stage('Archive Terraform State') {
                archiveArtifacts artifacts: "results/sumaform/terraform.tfstate, results/sumaform/terraform.tfstate.backup", allowEmptyArchive: true
            }
        }
    }
}

return this

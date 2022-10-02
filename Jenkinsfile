pipeline {
    agent any

    options {
        buildDiscarder(logRotator(numToKeepStr: '5'))
    }

    environment {
        deploy_key = "srv.tt-rss.org"
        deploy_host = "tt-rss.fakecake.org"
    }

    stages {
        stage('build') {
            steps {
                withCredentials([string(credentialsId: 'fdroid.jks', variable: 'FDROID_PASSWORD')]) {
                sh("bash ./gradlew assembleFdroid " +
                    "-PFDROID_STORE_FILE=/var/jenkins_home/android-jks/fdroid.jks " +
                    "-PFDROID_STORE_PASSWORD=$FDROID_PASSWORD " +
                    "-PFDROID_KEY_ALIAS=fdroid " +
                    "-PFDROID_KEY_PASSWORD=$FDROID_PASSWORD")
                }
            }
        }
        stage('archive') {
            steps {
                archiveArtifacts '**/*.apk'
            }
        }
        stage('deploy') {
            when {
               branch 'master'
            }
            steps {
                sshagent(credentials: ["${deploy_key}"]) {
                    script {
                        def files = findFiles(glob: '**/*.apk')

                        for (String file : files) {
                            sh("scp -oStrictHostKeyChecking=no ${file} ${deploy_host}:fdroid/repo/")
                        }

                        sh("ssh -oStrictHostKeyChecking=no ${deploy_host} sudo /usr/local/sbin/fdroid-update-repo")
                    }
                }
            }
        }
    }
    post {
        failure {
             mail body: "Project: ${env.JOB_NAME} <br>Build Number: ${env.BUILD_NUMBER}<br> build URL: ${env.BUILD_URL}",
                charset: 'UTF-8', from: 'jenkins@fakecake.org',
                mimeType: 'text/html',
                subject: "Build failed: ${env.JOB_NAME}",
                to: "fox@fakecake.org";
         }
    }
}

// vars/appdevicesNodePipeline.groovy
def call(Map pipelineParams) {
    pipeline {
        agent any
        stages {
            stage('checkout git') {
                steps {
                    echo "Just saying hello world:" + pipelineParams.name
                }
            }

            stage('build') {
                steps {
                    echo "Just saying hello world, but this time in a build"
                }
            }

            stage ('test') {
                steps {
                    echo "Just saying hello world, but this time running tests"
                }
            }
        }
/*
        post {
            failure {
                mail to: pipelineParams.email, subject: 'Pipeline failed', body: "${env.BUILD_URL}"
            }
        }
*/
    }
}
// vars/appdevicesNodePipeline.groovy

def withDockerCompose(Closure body) {
  docker.image('docker/compose:1.24.1').inside("-v /var/run/docker.sock:/var/run/docker.sock --entrypoint=''", body)
}

def withSonarScanner(Closure body) {
  docker.image('newtmitch/sonar-scanner:alpine').inside("--entrypoint=''", body)
}

def call(Map pipelineParams) {
  /* "GLOBAL" SCRIPTED VARIABLES */
  def version // artifact version to publish

  pipeline {
    agent none
    options { disableConcurrentBuilds() }
    stages {
      stage("Checkout") {
        steps {
          checkoutWithEnv()
        }
      }

      stage("Semantic Version") {
        steps {
          script {
            version = getSemver("master")
            echo version
            echo "Just saying hello world:" + pipelineParams.name
          }
        }
      }

      /*stage("Skip CI?") {
        steps {
          script {
            def tagForHead = sh(script: "git tag -l --points-at HEAD", returnStdout: true).trim()

            if (tagForHead == version) {
              echo "This commit is already tagged with version ${tagForHead}. Aborting."
              currentBuild.result = "ABORTED"
              error "Skip CI"
            }
          }
        }
      }

      stage("Build") {
        steps {
          withDockerCompose {
            sh "docker-compose -p ${env.BUILD_TAG} build"
          }
        }
      }

      stage("Unit") {
        steps {
          withDockerCompose {
            sh "docker-compose -p ${env.BUILD_TAG} run --rm ai-appinsights-sgw &quot;test&quot;"
          }
        }
      }

      stage("SonarQube") {
        steps {
          withCredentials([string(credentialsId: 'sonar-token', variable: 'SONARQUBE_TOKEN')]) {
            withSonarScanner {
              sh "sonar-scanner -Dsonar.login=${env.SONARQUBE_TOKEN} -Dsonar.projectBaseDir=${env.WORKSPACE} -Dsonar.projectVersion=${version}"
            }
          }
        }
      }

      stage("Audit") {
        steps {
          withDockerCompose {
            sh "docker-compose -p ${env.BUILD_TAG} run --rm ai-appinsights-sgw audit"
          }
        }
      }

      stage("Smoke") {
        steps {
          withDockerCompose {
            sh "docker-compose -p ${env.BUILD_TAG} run --rm ai-appinsights-sgw-smoke"
          }
        }
      }

      stage("Publish") {
        steps {
          script {
            docker.withRegistry("https://docker.appdirect.tools", "docker-rw") {
              docker.image("docker.appdirect.tools/ai-appinsights-sgw/ai-appinsights-sgw").push(version)
              docker.image("docker.appdirect.tools/ai-appinsights-sgw/ai-appinsights-sgw-smoke").push(version)
            }
          }
        }
      }

      stage("Increment Version") {
        steps {
          sh "npm version ${version} --no-git-tag-version"
        }
      }

      stage("Save NPM Credentials") {
        steps {
          withCredentials([[
                $class          : "UsernamePasswordMultiBinding",
                credentialsId   : "artifactory-username-password",
                usernameVariable: "ARTIFACTORY_USER",
                passwordVariable: "ARTIFACTORY_PASSWD"
              ]]) {
            sh "curl -u${env.ARTIFACTORY_USER}:${env.ARTIFACTORY_PASSWD} 'https://artifactory.appdirect.tools/artifactory/api/npm/npm-local/auth/appdirect' >> .npmrc"
          }
        }
      }

      stage("Publish NPM") {
        steps {
          sh "npm publish --scope=@appdirect"
        }
      }

      stage("Push Git Tags") {
        when { branch "master-ad-migrate" }
        steps {
          sshagent(credentials: ["jenkins-github"]) {
            sh "git tag ${version}"
            sh "git push origin --tags"
          }
        }
      }*/
    }
  }
  /*post {
    always {
      withDockerCompose {
        sh "docker-compose -p ${env.BUILD_TAG} down --volumes --remove-orphans"
        sh "docker-compose rm --force"
      }

      slackBuildStatus pipelineParams.slackChannel, env.SLACK_USER
    }
  }*/
}
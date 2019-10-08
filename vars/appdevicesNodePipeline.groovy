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
  def appName

  pipeline {
    agent any
    options { disableConcurrentBuilds() }
    stages {
      stage("Checkout") {
        steps {
          checkoutWithEnv([
            $class           : "GitSCM",
            branches         : scm.branches,
            userRemoteConfigs: scm.userRemoteConfigs,
            extensions       : [
              [$class: "CloneOption", noTags: false],
              [$class: "LocalBranch", localBranch: "**"]
            ]
          ])
        }
      }

      stage("Setup") {
        steps {
          script {
            appName = adenv.getREPO_NAME()
            echo version
          }
        }
      }

      stage("Semantic Version") {
        steps {
          script {
            version = getSemver("master")
            echo version
          }
        }
      }

      stage("Skip CI?") {
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
            sh "docker-compose -p ${env.BUILD_TAG} run --rm ${appName} test"
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

      stage("Smoke") {
        steps {
          withDockerCompose {
            sh "docker-compose -p ${env.BUILD_TAG} run --rm ${appName}-smoke"
          }
        }
      }

      /*stage("Publish") {
        steps {
          script {
            docker.withRegistry("https://docker.appdirect.tools", "docker-rw") {
              docker.image("docker.appdirect.tools/${appName}/${appName}").push(version)
              docker.image("docker.appdirect.tools/${appName}/${appName}-smoke").push(version)
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
  post {
    always {
      withDockerCompose {
        sh "docker-compose -p ${env.BUILD_TAG} down --volumes --remove-orphans"
        sh "docker-compose rm --force"
      }

      slackBuildStatus pipelineParams.slackChannel, env.SLACK_USER
    }
  }
}
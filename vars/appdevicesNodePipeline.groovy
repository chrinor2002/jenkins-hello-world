// vars/appdevicesNodePipeline.groovy

// Utils
// ================================================================================
def withDockerCompose(Closure body) {
  docker.image('docker/compose:1.24.1').inside("-v /var/run/docker.sock:/var/run/docker.sock --entrypoint=''", body)
}

def withSonarScanner(Closure body) {
  docker.image('newtmitch/sonar-scanner:alpine').inside("--entrypoint=''", body)
}

def getConfig(params, defaults, team_defaults) {
  def config = [:]
  config << defaults
  if (params.USE_TEAM_DEFAULTS) {
    // if the team is not found, we want errors
    config << team_defaults[params.USE_TEAM_DEFAULTS]
  }
  config << [
    APP_NAME: adenv.getREPO_NAME()
  ]
  config << params
  return config
}

def call(Map pipelineParams) {

  // Constants
  // ================================================================================
  def USE_TEAM_DEFAULTS = 'USE_TEAM_DEFAULTS'
  def APP_NAME = 'APP_NAME'
  def NODE_VERSION = 'NODE_VERSION'
  def SONAR_URL = 'SONAR_URL'
  def SLACK_CHANNEL = 'SLACK_CHANNEL'
  def MASTER_SCHEMA_ENABLED = 'MASTER_SCHEMA_ENABLED'
  def RELEASE_BRANCH = 'RELEASE_BRANCH'

  def DEFAULTS = [
    NODE_VERSION: 10,
    SONAR_URL: 'https://sonar.appdirect.tools',
    SLACK_CHANNEL: '#override-with-real-channel',
    MASTER_SCHEMA_ENABLED: true,
    RELEASE_BRANCH: 'master'
  ]

  def TEAM_DEFAULTS = [
    appinsights: [
      NODE_VERSION: 8,
      SLACK_CHANNEL: '#appinsights-git'
    ]
  ]

  // npm repo cannot be overriden, its CI/CD controlled
  def npmRepo = 'https://artifactory.appdirect.tools/artifactory/api/npm/npm-local'

  def version // artifact version to publish
  def config // the aggregate pipeline config

  pipeline {
    agent any
    options { disableConcurrentBuilds() }
    stages {
      stage("Setup Pipeline Config") {
        steps {
          script {
            config = getConfig(pipelineParams, DEFAULTS, TEAM_DEFAULTS)
          }
        }
      }
      stage("Checkout") {
        steps {
          checkoutWithEnv()
        }
      }

      stage("Setup") {
        steps {
          script {
            sh "test -f sonar-project.properties || cat <<EOF > sonar-project.properties\n\
sonar.host.url=${config[SONAR_URL]}\n\
sonar.projectKey=${config[APP_NAME]}\n\
sonar.projectName=${config[APP_NAME]}\n\
sonar.sources=.\n\
sonar.exclusions=test/**/*,tmp/**/*,node_modules/**/*\n\
sonar.tests=test\n\
sonar.test.exclusions=test/fixtures/**/*\n\
sonar.javascript.file.suffixes=.js\n\
sonar.javascript.lcov.reportPaths=tmp/coverage/reports/lcov.info\n\
EOF\n"
            sh "test -f .dockerignore || cat <<EOF > .dockerignore\n\
# dependencies\n\
node_modules\n\
\n\
# testing\n\
coverage\n\
.scannerwork\n\
?\n\
\n\
# artifacts\n\
build\n\
dist\n\
\n\
# pipeline\n\
.deployments\n\
.dockerignore\n\
.editorconfig\n\
docker-compose.yaml\n\
Dockerfile\n\
Jenkinsfile\n\
sonar-project.properties\n\
\n\
# git\n\
.github\n\
.gitignore\n\
.git\n\
\n\
# IDEs\n\
.vscode\n\
.idea\n\
\n\
# misc\n\
.DS_Store\n\
\n\
npm-debug.log*\n\
yarn-debug.log*\n\
yarn-error.log*\n\
EOF"
          }
        }
      }

      stage("Semantic Version") {
        steps {
          script {
            version = getSemver("migrate")
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
          script {
            if (config[MASTER_SCHEMA_ENABLED]) {
              echo "TODO: generate master schema"
              // sh "docker run --rm -v $PWD:/node docker.appdirect.tools/node-dev-${config[NODE_VERSION]} $LIBRARY/master_schema.js /node/master_schema.json --WMUseSimpleLogger --WMIgnoreNoPropertiesFiles"
            } else {
              echo "Master schema generation disabled"
            }
          }
        }
      }

      stage("Testing prep") {
        steps {
          withDockerCompose {
            // This "forces" the compose env to get ready for a bunch of parallel jobs
            sh "docker-compose -p ${env.BUILD_TAG} run --rm --entrypoint sh ${config[APP_NAME]} -e 'echo setup...'"
          }
        }
      }

      stage("Testing") {
        steps {
          parallel(
            Unit: {
              withDockerCompose {
                sh "docker-compose -p ${env.BUILD_TAG} run --rm ${config[APP_NAME]} test"
              }
            },
            Smoke: {
              withDockerCompose {
                sh "docker-compose -p ${env.BUILD_TAG} run --rm ${config[APP_NAME]}-smoke"
              }
            },
            Integration: {
              withDockerCompose {
                sh "docker-compose -p ${env.BUILD_TAG} run --rm ${config[APP_NAME]}-integration"
              }
            },
            Contract: {
              withDockerCompose {
                sh "docker-compose -p ${env.BUILD_TAG} run --rm --entrypoint sh ${config[APP_NAME]} -e 'echo TODO: run contract tests'"
              }
            }
          )
        }
      }

      // Note: Scanning needs coverage info produced from test step
      stage("SonarQube") {
        steps {
          withCredentials([string(credentialsId: 'sonar-token', variable: 'SONARQUBE_TOKEN')]) {
            withSonarScanner {
              echo "TOOD: enable sonar scanning"
              /*sh "sonar-scanner \
              -Dsonar.login=${env.SONARQUBE_TOKEN} \
              -Dsonar.projectBaseDir=${env.WORKSPACE} \
              -Dsonar.projectVersion=${version}"*/
            }
          }
        }
      }

      /*stage("Publish") {
        steps {
          script {
            docker.withRegistry("https://docker.appdirect.tools", "docker-rw") {
              docker.image("docker.appdirect.tools/${config[APP_NAME]}/${config[APP_NAME]}").push(version)
              docker.image("docker.appdirect.tools/${config[APP_NAME]}/${config[APP_NAME]}-smoke").push(version)
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
            sh "curl -u${env.ARTIFACTORY_USER}:${env.ARTIFACTORY_PASSWD} '${npmRepo}/auth/appdirect' >> .npmrc"
          }
        }
      }

      stage("Publish NPM") {
        steps {
          sh "npm publish"
        }
      }

      stage("Push Git Tags") {
        when { branch "migrate" }
        steps {
          sshagent(credentials: ["jenkins-github"]) {
            sh "git tag ${version}"
            sh "git push origin --tags"
          }
          script {
            echo "TODO: create release notes"
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

      slackBuildStatus config[SLACK_CHANNEL], env.SLACK_USER
    }
  }
}
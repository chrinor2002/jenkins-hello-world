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
  def Map config
  config << defaults
  if (params.USE_TEAM_DEFAULTS) {
    // if the team is not found, we want errors
    config << team_defaults[params.USE_TEAM_DEFAULTS]
  }
  config << {
    APP_NAME: adenv.getREPO_NAME()
  }
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

  def DEFAULTS = {
    NODE_VERSION: 10
    SONAR_URL: 'https://sonar.appdirect.tools'
    SLACK_CHANNEL: '#override-with-real-channel'
    MASTER_SCHEMA_ENABLED: true
    RELEASE_BRANCH: 'master'
  }

  def TEAM_DEFAULTS = {
    appinsights: {
      NODE_VERSION: 8
      SLACK_CHANNEL: '#appinsights-git'
    }
  }

  // npm repo cannot be overriden, its a CI/CD controlled
  def npmRepo = 'https://artifactory.appdirect.tools/artifactory/api/npm/npm-local'

  def version // artifact version to publish
  def config = getConfig(pipelineParams, DEFAULTS, TEAM_DEFAULTS)

  pipeline {
    agent any
    options { disableConcurrentBuilds() }
    stages {
      stage("Checkout") {
        steps {
          checkoutWithEnv()
        }
      }

      stage("Setup") {
        steps {
          script {
            if (!fileExists('sonar-project.properties')) {
              writeFile(file: 'sonar-project.properties', text: """
sonar.host.url=${config.SONAR_URL}
sonar.projectKey=${config.APP_NAME}
sonar.projectName=${config.APP_NAME}
sonar.sources=.
sonar.exclusions=test/**/*,tmp/**/*,node_modules/**/*
sonar.tests=test
sonar.test.exclusions=test/fixtures/**/*
sonar.javascript.file.suffixes=.js
sonar.javascript.lcov.reportPaths=tmp/coverage/reports/lcov.info
""")
            }
            if (!fileExists('Dockerfile')) {
              writeFile(file: 'Dockerfile', text: """
FROM node:${config.NODE_VERSION}-alpine

WORKDIR /home/node
USER node:node

COPY --chown=node:node .npmrc package.json package-lock.json ./
RUN npm ci && \
    npm cache clean --force

COPY --chown=node:node . ./

EXPOSE 8101
ENTRYPOINT ["npm"]
CMD ["start"]
""")
            }
            if (!fileExists('.dockerignore')) {
              writeFile(file: '.dockerignore', text: """
# dependencies
node_modules

# testing
coverage
.scannerwork
?

# artifacts
build
dist

# pipeline
.deployments
.dockerignore
.editorconfig
docker-compose.yaml
Dockerfile
Jenkinsfile
sonar-project.properties

# git
.github
.gitignore
.git

# IDEs
.vscode
.idea

# misc
.DS_Store

npm-debug.log*
yarn-debug.log*
yarn-error.log*
""")
            }
            if (!fileExists('.npmignore')) {
              writeFile(file: '.npmignore', text: """
# dependencies
node_modules

# testing
coverage
.scannerwork
?

# pipeline
.deployments
.dockerignore
.editorconfig
docker-compose.yaml
Dockerfile
Jenkinsfile
sonar-project.properties

# git
.github
.gitignore
.git

# IDEs
.vscode
.idea

# misc
.DS_Store

npm-debug.log*
yarn-debug.log*
yarn-error.log*
""")
            }
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

      stage("Testing") {
        steps {
          parallel(
            Unit: {
              withDockerCompose {
                sh "docker-compose -p ${env.BUILD_TAG} run --rm ${config.APP_NAME} test"
              }
            },
            Smoke: {
              withDockerCompose {
                sh "docker-compose -p ${env.BUILD_TAG} run --rm ${config.APP_NAME}-smoke"
              }
            },
            Integration: {
              withDockerCompose {
                sh "docker-compose -p ${env.BUILD_TAG} run --rm ${config.APP_NAME}-integration"
              }
            }
          )
          
          
        }
      }

      /*stage("Publish") {
        steps {
          script {
            docker.withRegistry("https://docker.appdirect.tools", "docker-rw") {
              docker.image("docker.appdirect.tools/${config.APP_NAME}/${config.APP_NAME}").push(version)
              docker.image("docker.appdirect.tools/${config.APP_NAME}/${config.APP_NAME}-smoke").push(version)
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
          sh "npm publish --scope=@appdirect"
        }
      }

      stage("Push Git Tags") {
        when { branch "migrate" }
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

      slackBuildStatus config.SLACK_CHANNEL, env.SLACK_USER
    }
  }
}
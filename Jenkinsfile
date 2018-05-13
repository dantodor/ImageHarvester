#!groovy

String GIT_VERSION

node {

  def buildEnv

  stage ('Checkout') {
    deleteDir()
    checkout scm
    GIT_VERSION = sh (
      script: 'git describe --tags',
      returnStdout: true
    ).trim()
  }

  stage ('Build Custom Environment') {
    buildEnv = docker.build("build_env:test", 'docker-build-env')
  }

  buildEnv.inside {

    stage ('Build') {
      sh 'mvn compile'
    }

    stage ('Test') {
          sh 'mvn test'
    }

    stage ('Prepare Docker jar') {
      sh 'mvn -Dmaven.test.skip=true clean install package -DskipTests'
      sh 'mv harvester-server/target/harvester-server-0.3-SNAPSHOT-allinone.jar harvester-server/docker/harvester.jar'
    }
  }

  stage ('Build and Push Docker Image') {
    withCredentials([[$class: "UsernamePasswordMultiBinding", usernameVariable: 'DOCKERHUB_USER', passwordVariable: 'DOCKERHUB_PASS', credentialsId: 'dockerhub']]) {
      sh 'docker login --username $DOCKERHUB_USER --password $DOCKERHUB_PASS'
    }
    def serverImage = docker.build("dantodor/imh:${GIT_VERSION}", 'harvester-server/docker')
    serverImage.push()
    sh 'docker logout'
  }


}



pipeline {
    agent any
    tools {
        maven 'Maven 3.3.9'
        jdk 'jdk8'
    }
    stages {
        stage ('Initialize') {
            steps {
                sh '''
                    echo "PATH = ${PATH}"
                    echo "M2_HOME = ${M2_HOME}"
                '''
            }
        }

        stage ('Build') {
            steps {
                sh 'mvn compile'
            }
        }

        stage ('Test') {
            steps {
                sh 'mvn test'
            }
        }

        stage ('Prepare Docker jar') {
            steps {
                sh 'mvn -Dmaven.test.skip=true clean install package -DskipTests'
                sh 'mv harvester-server/target/harvester-server-0.3-SNAPSHOT-allinone.jar harvester-server/docker/harvester.jar'
            }
        }

        stage ('Build and Push Docker Image') {
            steps {
                withCredentials([[$class: "UsernamePasswordMultiBinding", usernameVariable: 'DOCKERHUB_USER', passwordVariable: 'DOCKERHUB_PASS', credentialsId: 'dockerhub']]) {
                    sh 'docker login --username $DOCKERHUB_USER --password $DOCKERHUB_PASS'
                }
                def serverImage = docker.build("dantodor/imh:1", 'harvester-server/docker')
                serverImage.push()
                sh 'docker logout'
            }
        }

    }
}






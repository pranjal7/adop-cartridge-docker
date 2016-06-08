// Folders
def workspaceFolderName = "${WORKSPACE_NAME}"
def projectFolderName = "${PROJECT_NAME}"

// Jobs for docker file pipeline
def getDockerfile = freeStyleJob(projectFolderName + "/Get_Dockerfile")
def staticCodeAnalysis = freeStyleJob(projectFolderName + "/Static_Code_Analysis")
def build = freeStyleJob(projectFolderName + "/Build")
def vulnerabilityScan = freeStyleJob(projectFolderName + "/Vulnerability_Scan")
def imageTest = freeStyleJob(projectFolderName + "/Image_Test")
def containerTest = freeStyleJob(projectFolderName + "/Container_Test")
def publish = freeStyleJob(projectFolderName + "/Publish")

// Views
def pipelineView1 = buildPipelineView(projectFolderName + "/Docker_Pipeline")

pipelineView1.with{
    title('Docker Pipeline')
    displayedBuilds(5)
    selectedJob(projectFolderName + "/Get_Dockerfile")
    showPipelineParameters()
    showPipelineDefinitionHeader()
    refreshFrequency(5)
}

getDockerfile.with{
  description("This job downloads the Dockerfile (and local resources) from the specified repository.")
  parameters{
    stringParam("APP_NAME",'',"Application name")
    stringParam("APP_REPO",'',"Application repository location")
    stringParam("APP_IMAGE",'',"Application image name")
    stringParam("TRUSTED_REGISTRY",'',"Docker trusted registry url")
  }
  wrappers {
    preBuildCleanup()
    injectPasswords()
    maskPasswords()
    sshAgent("adop-jenkins-master")
  }
  scm{
    git{
      remote{
        url('${APP_REPO}')
        credentials("adop-jenkins-master")
      }
      branch("*/master")
    }
  }
  environmentVariables {
      env('WORKSPACE_NAME',workspaceFolderName)
      env('PROJECT_NAME',projectFolderName)
  }
  label("docker")
  steps {
    shell('''set -xe
            |echo "Pull the Dockerfile out of Git ready for us to test and if successful release via the pipeline"
            |set +xe'''.stripMargin())
  }
  publishers{
    archiveArtifacts("**/*")
    downstreamParameterized{
      trigger(projectFolderName + "/Static_Code_Analysis"){
        condition("UNSTABLE_OR_BETTER")
        parameters{
          predefinedProp("B",'${BUILD_NUMBER}')
          predefinedProp("PARENT_BUILD",'${JOB_NAME}')
          predefinedProp("APP_NAME",'${APP_NAME}')
          predefinedProp("APP_REPO",'${APP_REPO}')
          predefinedProp("APP_IMAGE",'${APP_IMAGE}')
          predefinedProp("TRUSTED_REGISTRY",'${TRUSTED_REGISTRY}')
        }
      }
    }
  }
}

staticCodeAnalysis.with{
  description("This job performs static code analysis on the Dockerfile using the redcoolbeans dockerlint image. It assumes that the Dockerfile of the downloaded repository exists in the root of the directory structure.")
  parameters{
    stringParam("B",'',"Parent build number")
    stringParam("PARENT_BUILD","Get_Dockerfile","Parent build name")
    stringParam("APP_NAME",'',"Application name")
    stringParam("APP_REPO",'',"Application repository location")
    stringParam("APP_IMAGE",'',"Application image name")
    stringParam("TRUSTED_REGISTRY",'',"Docker trusted registry url")
  }
  environmentVariables {
      env('WORKSPACE_NAME',workspaceFolderName)
      env('PROJECT_NAME',projectFolderName)
  }
  wrappers {
    preBuildCleanup()
    injectPasswords()
    maskPasswords()
    sshAgent("adop-jenkins-master")
  }
  label("docker")
  steps {
    copyArtifacts('Get_Dockerfile') {
        buildSelector {
          buildNumber('${B}')
      }
    }
    shell('''set -x
            |echo "Mount the Dockerfile into a container that will run Dockerlint https://github.com/projectatomic/dockerfile_lint"
            |docker run --rm -v jenkins_slave_home:/jenkins_slave_home/ --entrypoint="dockerlint" redcoolbeans/dockerlint -f /jenkins_slave_home/$JOB_NAME/Dockerfile > ${WORKSPACE}/${JOB_NAME##*/}.out
            |#if ! grep "Dockerfile is OK" ${WORKSPACE}/${JOB_NAME##*/}.out; then
            |# exit 1
            |#fi
            |set +x'''.stripMargin())
  }
  publishers{
    archiveArtifacts("**/*")
    downstreamParameterized{
      trigger(projectFolderName + "/Build"){
        condition("UNSTABLE_OR_BETTER")
        parameters{
          predefinedProp("B",'${B}')
          predefinedProp("PARENT_BUILD", '${PARENT_BUILD}')
          predefinedProp("APP_NAME",'${APP_NAME}')
          predefinedProp("APP_REPO",'${APP_REPO}')
          predefinedProp("APP_IMAGE",'${APP_IMAGE}')
          predefinedProp("TRUSTED_REGISTRY",'${TRUSTED_REGISTRY}')
        }
      }
    }
  }
}

build.with{
  description("This job builds the Docker image found in the root of the downloaded repository.")
  parameters{
    stringParam("B",'',"Parent build number")
    stringParam("PARENT_BUILD","Get_Dockerfile","Parent build name")
    stringParam("APP_NAME",'',"Application name")
    stringParam("APP_REPO",'',"Application repository location")
    stringParam("APP_IMAGE",'',"Application image name")
    stringParam("TRUSTED_REGISTRY",'',"Docker trusted registry url")
  }
  environmentVariables {
      env('WORKSPACE_NAME',workspaceFolderName)
      env('PROJECT_NAME',projectFolderName)
  }
  wrappers {
    preBuildCleanup()
    injectPasswords()
    maskPasswords()
    sshAgent("adop-jenkins-master")
  }
  label("docker")
  steps {
    copyArtifacts('Get_Dockerfile') {
        buildSelector {
          buildNumber('${B}')
      }
    }
    shell('''set -x
            |echo "Build the docker container image locally"
            |docker build -t ${APP_IMAGE}:${B} ${WORKSPACE}/.
            |set +x'''.stripMargin())
  }
  publishers{
    downstreamParameterized{
      trigger(projectFolderName + "/Vulnerability_Scan"){
        condition("UNSTABLE_OR_BETTER")
        parameters{
          predefinedProp("B",'${B}')
          predefinedProp("PARENT_BUILD", '${PARENT_BUILD}')
          predefinedProp("APP_NAME",'${APP_NAME}')
          predefinedProp("APP_REPO",'${APP_REPO}')
          predefinedProp("APP_IMAGE",'${APP_IMAGE}')
          predefinedProp("TRUSTED_REGISTRY",'${TRUSTED_REGISTRY}')
        }
      }
    }
  }
}

vulnerabilityScan.with{
  description("This job tests the image against a database of known vulnerabilities using Clair, an open source static analysis tool https://github.com/coreos/clair. It assumes that Clair has access to the image being tested.")
  parameters{
    stringParam("B",'',"Parent build number")
    stringParam("PARENT_BUILD","Get_Dockerfile","Parent build name")
    stringParam("APP_NAME",'',"Application name")
    stringParam("APP_REPO",'',"Application repository location")
    stringParam("APP_IMAGE",'',"Application image name")
    stringParam("TRUSTED_REGISTRY",'',"Docker trusted registry url")
  }
  wrappers {
    preBuildCleanup()
    injectPasswords()
    maskPasswords()
    sshAgent("adop-jenkins-master")
  }
  environmentVariables {
      env('WORKSPACE_NAME',workspaceFolderName)
      env('PROJECT_NAME',projectFolderName)
  }
  label("docker")
  steps {
    copyArtifacts("Get_Dockerfile") {
        buildSelector {
          buildNumber('${B}')
      }
    }
    shell('''set -x
            |echo "Use the darrenajackson/analyze-local-images container to analyse the image"
            |docker run --net=host --rm -v /tmp:/tmp -v /var/run/docker.sock:/var/run/docker.sock darrenajackson/analyze-local-images ${APP_IMAGE}:${B} > ${WORKSPACE}/${JOB_NAME##*/}.out
            |#if ! grep "^Success! No vulnerabilities were detected in your image$" ${WORKSPACE}/${JOB_NAME##*/}.out; then
            |# exit 1
            |#fi
            |set +x'''.stripMargin())
  }
  publishers{
    downstreamParameterized{
      trigger(projectFolderName + "/Image_Test"){
        condition("UNSTABLE_OR_BETTER")
        parameters{
          predefinedProp("B",'${B}')
          predefinedProp("PARENT_BUILD", '${PARENT_BUILD}')
          predefinedProp("APP_NAME",'${APP_NAME}')
          predefinedProp("APP_REPO",'${APP_REPO}')
          predefinedProp("APP_IMAGE",'${APP_IMAGE}')
          predefinedProp("TRUSTED_REGISTRY",'${TRUSTED_REGISTRY}')
        }
      }
    }
  }
}

imageTest.with{
  description("This job uses a python script to analyse the output from docker inspect against a configuration file that details required parameters. It also looks for any unexpected additions to the new image being tested. The configuration file must live under tests/image-test inside the images repository and be called <application_namei>.cfg.")
  parameters{
    stringParam("B",'',"Parent build number")
    stringParam("PARENT_BUILD","Get_Dockerfile","Parent build name")
    stringParam("APP_NAME",'',"Application name")
    stringParam("APP_REPO",'',"Application repository location")
    stringParam("APP_IMAGE",'',"Application image name")
    stringParam("TRUSTED_REGISTRY",'',"Docker trusted registry url")
  }
  wrappers {
    preBuildCleanup()
    injectPasswords()
    maskPasswords()
    sshAgent("adop-jenkins-master")
  }
  environmentVariables {
      env('WORKSPACE_NAME',workspaceFolderName)
      env('PROJECT_NAME',projectFolderName)
  }
  label("docker")
  steps {
    copyArtifacts("Get_Dockerfile") {
        buildSelector {
          buildNumber('${B}')
      }
    }
    shell('''set -x
            |echo "Use the darrenajackson/image-inspector container to inspect the image"
            |# set test file directory
            |export TESTS_PATH="tests/image-test"
            |# set directory where $TESTS_PATH will be mounted inside container
            |export TEST_DIR="/tmp"
            |# set path workspace is available from inside docker machine
            |export docker_workspace_dir=$(echo ${WORKSPACE} | sed 's#/workspace#/var/lib/docker/volumes/jenkins_slave_home/_data#')
            |docker run --net=host --rm -v ${docker_workspace_dir}/${TESTS_PATH}/:${TEST_DIR} -v /var/run/docker.sock:/var/run/docker.sock darrenajackson/image-inspector -i ${APP_IMAGE}:${B} -f ${TEST_DIR}/${APP_NAME}.cfg > ${WORKSPACE}/${JOB_NAME##*/}.out
            |#if grep "ERROR" ${WORKSPACE}/${JOB_NAME##*/}.out; then
            |# exit 1
            |#fi
            |set +x'''.stripMargin())
  }
  publishers{
    archiveArtifacts("**/*")
    downstreamParameterized{
      trigger(projectFolderName + "/Container_Test"){
        condition("UNSTABLE_OR_BETTER")
        parameters{
          predefinedProp("B",'${B}')
          predefinedProp("PARENT_BUILD",'${PARENT_BUILD}')
          predefinedProp("APP_NAME",'${APP_NAME}')
          predefinedProp("APP_REPO",'${APP_REPO}')
          predefinedProp("APP_IMAGE",'${APP_IMAGE}')
          predefinedProp("TRUSTED_REGISTRY",'${TRUSTED_REGISTRY}')
        }
      }
    }
  }
}

containerTest.with{
  description("This job creates a new testing image from the image being tested that also contains all the tools necessary for internal testing of the image. A series of tests are then run from inside the new container. The tests can be written in any fashion you wish, however they must be initiated from a file called container_tests.sh that lives inside tests/container-test in the images repository.")
  parameters{
    stringParam("B",'',"Parent build number")
    stringParam("PARENT_BUILD","Get_Dockerfile","Parent build name")
    stringParam("APP_NAME",'',"Application name")
    stringParam("APP_REPO",'',"Application repository location")
    stringParam("APP_IMAGE",'',"Application image name")
    stringParam("TRUSTED_REGISTRY",'',"Docker trusted registry url")
  }
  wrappers {
    preBuildCleanup()
    injectPasswords()
    maskPasswords()
    sshAgent("adop-jenkins-master")
  }
  environmentVariables {
      env('WORKSPACE_NAME',workspaceFolderName)
      env('PROJECT_NAME',projectFolderName)
  }
  label("docker")
  steps {
    copyArtifacts("Get_Dockerfile") {
        buildSelector {
          buildNumber('${B}')
      }
    }
    shell('''set -x
            |echo "Build a new test image installing required applications, run test suite and destroy the new image and container at the end of the tests"
            |# set test file directory
            |export TESTS_PATH="tests/container-test"
            |# set testing docker file info
            |export TEST_DF_PATH="${WORKSPACE}/${TESTS_PATH}/dockerfile"
            |export TEST_DF_NAME="Dockerfile.test"
            |export TEST_DF="${TEST_DF_PATH}/${TEST_DF_NAME}"
            |# set file containing list of environment variables
            |export TEST_ENVS="${WORKSPACE}/${TESTS_PATH}/envs/envs.cfg"
            |# test image name extension
            |export IMG_EXT="test"
            |# test image tag
            |export IMG_TAG=${B}
            |# set directory where $TESTS_PATH will be mounted inside container
            |export TEST_DIR="/var/tmp"
            |# set path workspace is available from inside docker machine
            |export docker_workspace_dir=$(echo ${WORKSPACE} | sed 's#/workspace#/var/lib/docker/volumes/jenkins_slave_home/_data#')
            |# source environment variables needed for test dockerfile generation
            |source ${WORKSPACE}/${TESTS_PATH}/dockerfile/dockerfile_envs.sh
            |# create test Dockerfile
            |if ! [[ -f ${TEST_DF} ]]; then
            |  cat << EOF > ${TEST_DF}
            |FROM ${APP_IMAGE}:${B}
            |
            |RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y ${PACK_LIST} && apt-get clean && rm -rf /var/lib/apt/lists/*
            |
            |EOF
            |fi
            |# if the environment variable file exists add them to the dockerfile
            |if [[ -f ${TEST_ENVS} ]]; then
            |  cat ${TEST_ENVS} >> ${TEST_DF}
            |fi
            |# build the test images
            |docker build -t ${APP_IMAGE}-${IMG_EXT}:${IMG_TAG} -f ${TEST_DF} ${TEST_DF_PATH}
            |# run the test image
            |docker run -d --name ${APP_NAME}-${IMG_EXT} -v ${docker_workspace_dir}/${TESTS_PATH}/:${TEST_DIR} ${APP_IMAGE}-${IMG_EXT}:${IMG_TAG}
            |# allow the container time to start
            |sleep 60
            |# execute the testing scripts
            |docker exec ${APP_NAME}-${IMG_EXT} ${TEST_DIR}/container_tests.sh ${TEST_DIR} > ${WORKSPACE}/${JOB_NAME##*/}.out
            |# stop and clean up the testing container and testing image
            |docker stop ${APP_NAME}-${IMG_EXT} && docker rm -v ${APP_NAME}-${IMG_EXT} && docker rmi ${APP_IMAGE}-${IMG_EXT}:${IMG_TAG}
            |#if grep "^-" ${WORKSPACE}/${JOB_NAME##*/}.out; then
            |# exit 1
            |#fi
            |set -x'''.stripMargin())
  }
  publishers{
    archiveArtifacts("**/*")
    downstreamParameterized{
      trigger(projectFolderName + "/Publish"){
        condition("UNSTABLE_OR_BETTER")
        parameters{
          predefinedProp("B",'${B}')
          predefinedProp("PARENT_BUILD",'${PARENT_BUILD}')
          predefinedProp("APP_NAME",'${APP_NAME}')
          predefinedProp("APP_REPO",'${APP_REPO}')
          predefinedProp("APP_IMAGE",'${APP_IMAGE}')
          predefinedProp("TRUSTED_REGISTRY",'${TRUSTED_REGISTRY}')
        }
      }
    }
  }
}

publish.with{
  description("This job pushes the new image that has been fully tested to the registry (not the test image from the containerTest).")
  parameters{
    stringParam("B",'',"Parent build number")
    stringParam("PARENT_BUILD","Get_Dockerfile","Parent build name")
    stringParam("APP_NAME",'',"Application name")
    stringParam("APP_REPO",'',"Application repository location")
    stringParam("APP_IMAGE",'',"Application image name")
    stringParam("TRUSTED_REGISTRY",'',"Docker trusted registry url")
  }
  wrappers {
    preBuildCleanup()
    injectPasswords()
    maskPasswords()
    sshAgent("adop-jenkins-master")
    credentialsBinding{
      string("docker_email", "dockerhub-email")
      string("docker_auth", "dockerhub-auths")
    }
  }
  environmentVariables {
      env('WORKSPACE_NAME',workspaceFolderName)
      env('PROJECT_NAME',projectFolderName)
  }
  label("docker")
  steps {
    copyArtifacts("Get_Dockerfile") {
        buildSelector {
          buildNumber('${B}')
      }
    }
    shell('''set -x
            |echo "pushing the new image to the registry"
            |# dockerhub authentication file generation
            |export DOCKER_CONF_DIR="/root/.docker"
            |export DOCKER_CONF_FILE="config.json"
            |export DOCKER_CONF="${DOCKER_CONF_DIR}/${DOCKER_CONF_FILE}"
            |touch ${WORKSPACE}/${DOCKER_CONF_FILE}
            |cat <<EOF > ${WORKSPACE}/${DOCKER_CONF_FILE}
            |{
            |    "auths": {
            |        "${TRUSTED_REGISTRY}": {
            |            "auth": "${docker_auth}",
            |            "email": "${docker_email}"
            |        }
            |    }
            |}
            |EOF
            |# copy dockerhub authentication file into jenkins-slave container
            |docker cp ${WORKSPACE}/${DOCKER_CONF_FILE} jenkins-slave:${DOCKER_CONF}
            |# push the new image to dockerhub
            |docker push ${APP_IMAGE}:${B}
            |# tidy up and delete image pushed to dockerhub
            |docker rmi ${APP_IMAGE}:${B}
            |# clean up dockerhub authentication file in jenkins-slave
            |docker exec jenkins-slave rm -f ${DOCKER_CONF}
            |# clean up dockerhub authentication file in workspace
            |rm -f ${WORKSPACE}/${DOCKER_CONF_FILE}
            |set +x'''.stripMargin())
  }
}

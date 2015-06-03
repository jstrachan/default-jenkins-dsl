import io.fabric8.repo.git.GitRepoClient;

// lets define the organsations and projects to include/exclude
def excludedProjectNames = []
def includedProjectNames = []

mavenJob('base-maven-build') {
    keepDependencies(false)

    logRotator(
            1, // days to keep
            5, // num to keep
            -1, // artifact days to keep
            -1 // artifact num to keep
    )

    wrappers {
        timestamps()
        colorizeOutput()
        maskPasswords()
        timeout {
            elastic(
                    450, // Build will timeout when it take 3 time longer than the reference build duration, default = 150
                    5,   // Number of builds to consider for average calculation
                    120   // 30 minutes default timeout (no successful builds available as reference)
            )
            failBuild()
        }
    }
    mavenInstallation('3.3.1')
    localRepository(LocalRepositoryLocation.LOCAL_TO_WORKSPACE)
}

freeStyleJob('base-freestyle-build') {
    wrappers {
        timestamps()
        colorizeOutput()
        maskPasswords()
        golang('1.4.2')
        timeout {
            elastic(
                    150, // Build will timeout when it take 3 time longer than the reference build duration, default = 150
                    3,   // Number of builds to consider for average calculation
                    30   // 30 minutes default timeout (no successful builds available as reference)
            )
            failBuild()
        }
    }
}

// lets try find the class loader for the spring stuff and where it comes from:
def clazz = org.springframework.util.ClassUtils.class
def cloader = clazz.getClassLoader()
println "got class ${clazz} from class loader ${cloader}"
def urls = cloader.getURLs()
urls.each { url -> 
   println "got URL: ${url}"
}



def username = 'ceposta'
def password = 'RedHat$1'
def client = new GitRepoClient('http://gogs.vagrant.local/', username, password)
repos = client.listRepositories()
repos.each { repo ->
    def fullName = repo.getFullName()
    def gitUrl = repo.getCloneUrl()
    def repoName = fullName.substring(fullName.indexOf("/") + 1)

    println "Found repo name: ${repoName}, full: ${fullName}, clone url: ${gitUrl}"

    if (!excludedProjectNames.contains(repoName) && (includedProjectNames.contains(repoName) || includedProjectNames.isEmpty())) {
        println "Adding repo ${repoName} to jenkins build"
        createJobs(repoName, fullName, gitUrl, username, password)

    }
}

def createJobs(repoName, fullName, gitUrl, username, password) {
    /**
     * CI Build
     */
    mavenJob(repoName + "-ci") {
        using('base-maven-build')
        description('Run the build and the unit tests with a specific version. If they pass, move it to the next step.')
        blockOnDownstreamProjects()

        parameters {
            stringParam(
                    'MAJOR_VERSION_NUMBER', // param name
                    '1.0', // default value
                    'The major version. We will not use SNAPSHOTs for CD, so need to give a real version'
            )
        }
        scm {
            git(gitUrl, '*/master') {
                clean(true)
                createTag(false)
                cloneTimeout(30)
            }
        }
        publishers {
            downstreamParameterized {
                trigger(repoName + "-it", 'SUCCESS', false){
                    predefinedProp('TAG_PREFIX', repoName)
                    predefinedProp('RELEASE_NUMBER', '$MAJOR_VERSION_NUMBER.$BUILD_NUMBER')
                }
            }
        }
        preBuildSteps {
            shell('git checkout -b ' + repoName + '-$MAJOR_VERSION_NUMBER.$BUILD_NUMBER')
            maven('versions:set -DnewVersion=$MAJOR_VERSION_NUMBER.$BUILD_NUMBER')
        }
        postBuildSteps {
            conditionalSteps {
                condition {
                    status("SUCCESS", "SUCCESS")
                }
                // todo this is hardcoded (the location of the git repo; this should use a kube service host/port
                // todo or do we just use the gitUrl we got back from the /user/repos call and hack in the un/pw?
                // or use ssh keys?
                shell('git commit -a -m \'new release candidate\' \n ' +
                      'git push http://'+username+':'+password+'@gogs.vagrant.local/' + fullName + '.git ' + repoName + '-$MAJOR_VERSION_NUMBER.$BUILD_NUMBER')
            }
            conditionalSteps {
                condition {
                    status("FAILURE", "FAILURE")
                }
                shell('git branch -D '+repoName+'-$MAJOR_VERSION_NUMBER.$BUILD_NUMBER')
            }

        }

        goals('clean install')
    }

    /**
     * Integration Test Build
     */
    mavenJob(repoName + "-it") {
        using('base-maven-build')
        description('Run the itests for this module (expected to have a maven profile named itests. If they pass, move it to the next step.')
        parameters {
            stringParam(
                    'TAG_PREFIX', // prefix of the tag we created in the CI step, usually the repo name
                    repoName, // default value
                    'The tag prefix we created for the build if it passed the CI step'
            )
            stringParam(
                    'RELEASE_NUMBER', // the release we pushed to nexus and git repo
                    '', // no good default for this at the moment
                    'The release we pushed to nexus and git repo'
            )
        }
        scm {
            git(gitUrl, '${TAG-PREFIX}-${RELEASE_NUMBER}') {
                clean(true)
                createTag(false)
                cloneTimeout(30)
            }
        }
        publishers {
            downstreamParameterized {
                trigger(repoName + "-dev-deploy", 'SUCCESS', false){
                    predefinedProp('TAG_PREFIX', '$TAG_PREFIX')
                    predefinedProp('RELEASE_NUMBER', '$RELEASE_NUMBER')
                }
            }
        }
        goals('verify -Pitests')
    }

    /**
     * Deploy to DEV
     */
    freeStyleJob(repoName + "-dev-deploy") {
        using('base-freestyle-build')
        parameters {
            stringParam(
                    'TAG_PREFIX', // prefix of the tag we created in the CI step, usually the repo name
                    repoName, // default value
                    'The tag prefix we created for the build if it passed the CI step'
            )
            stringParam(
                    'RELEASE_NUMBER', // the release we pushed to nexus and git repo
                    '', // no good default for this at the moment
                    'The release we pushed to nexus and git repo'
            )
        }
        scm {
            git(gitUrl, '${TAG-PREFIX}-${RELEASE_NUMBER}') {
                clean(true)
                createTag(false)
                cloneTimeout(30)
            }
        }
        publishers {
            downstreamParameterized {
                trigger(repoName + "-dev-accept", 'SUCCESS', false){
                    predefinedProp('TAG_PREFIX', '$TAG_PREFIX')
                    predefinedProp('RELEASE_NUMBER', '$RELEASE_NUMBER')
                }
            }
        }
    }

    /**
     * Run Acceptance tests against DEV
     */
    mavenJob(repoName + "-dev-accept") {
        using('base-maven-build')
        parameters {
            stringParam(
                    'TAG_PREFIX', // prefix of the tag we created in the CI step, usually the repo name
                    repoName, // default value
                    'The tag prefix we created for the build if it passed the CI step'
            )
            stringParam(
                    'RELEASE_NUMBER', // the release we pushed to nexus and git repo
                    '', // no good default for this at the moment
                    'The release we pushed to nexus and git repo'
            )
        }
        scm {
            git(gitUrl) {
                branch('master')
                clean(true)
                createTag(false)
                cloneTimeout(30)
            }
        }

        goals('clean install')
    }

    buildPipelineView(repoName + "-cd-pipeline") {
        selectedJob(repoName + "-ci")
        title('Continuous Delivery pipeline for ' + repoName)
        refreshFrequency(30)
        showPipelineDefinitionHeader(true)
        showPipelineParameters(true)
        showPipelineParametersInHeaders(true)
        displayedBuilds(10)
        consoleOutputLinkStyle(OutputStyle.NewWindow)
    }

    buildMonitorView(repoName + "-cd-monitor") {
        description('All jobs for the ' + repoName + ' CD pipeline')
        jobs {
            name(repoName + "-ci")
            name(repoName + "-it")
            name(repoName + "-dev-deploy")
            name(repoName + "-dev-accept")
        }
    }
}



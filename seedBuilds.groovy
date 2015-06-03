import io.fabric8.repo.git.GitRepoClient;

// lets define the organsations and projects to include/exclude
def excludedProjectNames = []
def includedProjectNames = []

mavenJob('base-maven-build') {
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

def client = new GitRepoClient('http://gogs.vagrant.local/', 'ceposta', 'RedHat$1')
repos = client.listRepositories()
repos.each { repo ->
    def fullName = repo.getFullName()
    def gitUrl = repo.getCloneUrl()
    def repoName = fullName.substring(fullName.indexOf("/") + 1)

    println "Found repo name: ${repoName}, full: ${fullName}, clone url: ${gitUrl}"

    if (!excludedProjectNames.contains(repoName) && (includedProjectNames.contains(repoName) || includedProjectNames.isEmpty())) {
        println "Adding repo ${repoName} to jenkins build"

        mavenJob(repoName) {
            using('base-maven-build')
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
    }
}



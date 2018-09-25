// These are defined in here to make it as simple as possible to call steps within library methods,
// and also to make them relatively simple to call directly off of the 'oasis' global.
// https://jenkins.io/doc/book/pipeline/shared-libraries/#accessing-steps
def checkoutAnyBranch(String git_url, String refspec_flavor='github', String branch='**') {
    // A "normal" git checkout, but with provider-specific refspec flavors to make pull/merge
    // requests fetchable on checkout in Jenkins -- use the dir() directive to checkout into subdir.
    refspec = this.getRefspecMap(refspec_flavor)
    checkout(
        scm: [
             $class: 'GitSCM',
             branches: [[name: branch]],
             doGenerateSubmoduleConfigurations: false,
             userRemoteConfigs: [
                [
                    name: 'origin',
                    refspec: refspec,
                    url: git_url
                ]
            ]
        ]
    )
}

def configFromBody(body) {
    // Implementation of the Jenkinsfile "builder pattern", turns the closure body
    // into a config mapping, used to reduce Jenkinsfiles to pipeline configuration only.
    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()
    return config
}

def getRefspecMap(flavor) {
    // refspecs for various providers that include branches and changesets like merge and pull requests
    if (flavor == 'gitlab') {
        refspec = '+refs/heads/*:refs/remotes/origin/* +refs/merge-requests/*/head:refs/remotes/origin/mr/*'
    } else if (flavor == 'github') {
        refspec = '+refs/heads/*:refs/remotes/origin/* +refs/pull/*/head:refs/remotes/origin/pr/*'
    } else {
        error("Unknown refspec flavor: ${flavor}")
    }

    return refspec
}

def triggerBranch(flavor) {
    // Detect which branch to check out based on environment vars set by triggers.
    // Does not know which repository it's working with, so use this carefully, and
    // only when checking out repos associated with a build trigger that would set
    // the env vars being inspected here.

    // All remotes are assumed to be "origin".
    if (flavor == 'github' && env.ghprbPullId != null) {
        branch = 'origin/pr/' + env.ghprbPullId
    } else if (flavor == 'gitlab' && env.gitlabMergeRequestIid != null) {
        branch = 'origin/mr/' + env.gitlabMergeRequestIid
    } else {
        branch = "origin/master"
    }
    println "triggerBranch: " + branch
    return branch
}

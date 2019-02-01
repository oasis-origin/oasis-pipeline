package com.redhat.oasis

// Currently, the multistream pipeline is the only pipeline, but if/when the
// need arises to expand and classify common behaviors, we can do that.
class MultistreamMoleculePipeline implements Serializable {
    def job
    def env
    def config

    def MultistreamMoleculePipeline(job, config) {
        // job should be the "this" of the calling job, which gives this class access
        // to the globals (job steps, etc) available to the job itself
        this.job = job

        // keep a copy of the config map
        this.config = config.clone()
    }

    def configDefaults() {
        // mutates the config in-place to provide defaults
        // would ideally be called in the constructor, but its usage of pipeline-wrapped CPS callables
        // means that will not work: https://issues.jenkins-ci.org/browse/JENKINS-26313
        // as a result, it is called in runDefault as part of the "standard" oasis pipeline run

        // Set debug bool, OASIS_PIPELINE_DEBUG value takes precedence over config.debug
        // toBoolean is used in case the input value is, for some reason, a string 'true' or 'false'
        config.debug = getKey(job.params, 'OASIS_PIPELINE_DEBUG', getKey(config, 'debug', false)).toBoolean()

        // don't parallelize by default
        config.parallelize = getKey(config, 'parallelize', false)

        // Specify the molecule role name (effectively the name of the dir that the
        // upstream and downstream repos are combined into), default to the job's base name.
        config.molecule_role_name = getKey(config, 'molecule_role_name', job.env.JOB_BASE_NAME)

        // Similar to upstream/downstream git urls, make it easy to override the branches checked out.
        // Defaults to the 'triggerBranch' return, which will either be master or the triggering change
        config.upstream_git_branch = getKey(config, 'upstream_git_branch', job.oasis.triggerBranch('github'))
        config.downstream_git_branch = getKey(config, 'downstream_git_branch', job.oasis.triggerBranch('gitlab'))

        // No gitlab is updated by default for the checkout and test phases. Set this to the name of your
        // gitlab connection if you want to update gitlab with stage statuses.
        config.gitlab_connection = getKey(config, 'gitlab_connection', false)

        // Pipeline step hooks, usually empty closures by default.
        // Allow the definition of "pre" hooks for each stage, and "post" hooks for test and cleanup.
        // Adding additional hooks should be trivial if we need more.
        hookDefault('preSetUpHook')
        hookDefault('preCheckoutHook')
        hookDefault('prePrepareHook')
        hookDefault('preTestHook')
        hookDefault('postTestHook')
        hookDefault('cleanupHook')
    }

    def getKey(map, key, defaultValue) {
        // return the value for a given key, or the default value if the key is unset
        // explictly avoids mutating the map when getting the value for a key, apologies
        // if this is already implemented as a builtin somewhere
        return map.containsKey(key) ? map.get(key) : defaultValue
    }

    def hookDefault(key) {
        // Encapsulates the logic of ensuring a default closure supporting the oasis pipeline hook
        // interface (it takes a "config" arg) exists for a given config key.
        if (!config.containsKey(key)) {
            config[key] = {job.println "${key} not implemented"}
        } // else config[key] is already set, and we trust that the caller made it a closure.
    }

    def runDefault() {
        // default pipeline run, with all stages and hooks called
        // in their expected order.

        // Initialize the config with defaults
        configDefaults()

        // set the gitlab connection if configured to do so
        if (config.gitlab_connection) {
            job.properties([job.gitLabConnection(config.gitlab_connection)])
        }

        // Run through stages in their expected order
        try {
            job.stage('Set Up') {
                stageSetUpWorkspace()
            }

            job.stage('Checkout') {
                stageCheckout()
            }

            job.stage('Prepare') {
                stagePrepare()
            }

            job.stage('Test') {
                stageTest()
            }
        } finally {
            // Most hooks are run inside their respective stages, but cleanupHook
            // is special, and runs unconditionally regardless of what happens
            // in previous stages of the job.
            job.stage('Cleanup') {
                cleanupHook()
            }
        }
    }

    def updateGitlabStages(stage_states) {
        // Wrap gitlab stage status updates with a gitlab connection check
        // stage_states is a map where keys are stage names, and values are stage states
        if (config.gitlab_connection) {
            for (entry in stage_states) {
                job.updateGitlabCommitStatus name: entry.key, state: entry.value
            }
        }
    }

    def stageSetUpWorkspace() {
        try {
            preSetUpHook()
            // Notify gitlab of pending stages as soon as possible.
            // gitlab integrations should do nothing if no gitlab connection is configured
            updateGitlabStages Checkout: 'pending', Test: 'pending'

            // Clean the workspace, rather than in a post step, so that we can still
            // go exploring in the workspace after a failed job if needed.
            job.cleanWs()

            // Freestyle jobs store the environment vars by default, but pipelines don't
            // due to their multinode nature. In this pipeline, at least, we usually have one
            // node, so we'll print the env here to make debugging jobs a little easier.
            // We can wrap this in a debug flag, if desired.
            job.sh 'env|sort'
        } catch (exc) {
            // Don't really care what the exception was, just need to make sure that gitlab is
            // updated in the event that an error occurred.
            updateGitlabStages Checkout: 'canceled', Test: 'canceled'

            // re-throw the exception to ensure the build status changes accordingly
            throw exc
        }
    }

    def stageCheckout() {
        try {
            preCheckoutHook()
            updateGitlabStages Checkout: 'running'
            job.dir("${job.env.WORKSPACE}/.upstream") {
                job.oasis.checkoutAnyBranch(config.upstream_git_url, 'github', config.upstream_git_branch)
            }
            if (config.downstream_git_url != null) {
                job.dir("${job.env.WORKSPACE}/.downstream") {
                    job.oasis.checkoutAnyBranch(config.downstream_git_url, 'gitlab', config.downstream_git_branch)
                }
            }
            updateGitlabStages Checkout: 'success'
        } catch (exc) {
            updateGitlabStages Checkout: 'failed', Test: 'canceled'
            throw exc
        }
    }

    def stagePrepare() {
        try {
            prePrepareHook()
            if (config.downstream_git_url == null) {
                job.sh "mv '${job.env.WORKSPACE}/.upstream' '${job.env.WORKSPACE}/${config.molecule_role_name}'"
            } else {
                job.dir(config.molecule_role_name) {
                    job.sh "cp -r '${job.env.WORKSPACE}/.upstream/'* ."
                    job.sh "cp -r '${job.env.WORKSPACE}/.downstream/'* ."
                }
            }
            job.dir(config.molecule_role_name) {
                // We'll always need these for molecule + openstack
                // decorator is explicitly included because openstacksdk's requirements (at this time)
                // depend on a minimum version that is too low for the interfaces they use.
                // https://github.com/oasis-origin/oasis-pipeline/issues/1 for details.
                job.virtualenv('.venv', ['ansible', 'molecule', 'shade', 'openstacksdk', 'decorator'])

                // Also install and additional python/galaxy deps listed in the repo
                if (job.fileExists('requirements.txt')) {
                    job.venvSh('.venv', ['pip install -r requirements.txt'])
                }
                if (job.fileExists('requirements.yml')) {
                    job.venvSh('.venv', ['ansible-galaxy install -r requirements.yml'])
                }
            }
        } catch (exc) {
            updateGitlabStages Test: 'canceled'
            throw exc
        }
    }

    def stageTest() {
        try {
            preTestHook()
            updateGitlabStages Test: 'running'
            // include the trailing space when the ternary conditional evals true
            def debug = (config.debug) ? '--debug ' : ''
            def failed_scenarios = []

            // Create a closure for the actual testing, so that it's easy to pivot between parallel
            // or sequential scenario testing. This could be its own method, but declaring it this way
            // means that the failed_scenarios state tracking is easier.
            def test_scenario = {scenario ->
                job.stage("Scenario ${scenario}") {
                    def scenario_env = ["MOLECULE_EPHEMERAL_DIRECTORY=${job.env.WORKSPACE}/.molecule/${scenario}"]
                    try {
                        job.dir(config.molecule_role_name) {
                            job.withEnv(scenario_env) {
                                job.venvSh('.venv', ["molecule ${debug}test --destroy never -s ${scenario}"])
                            }
                        }
                    } catch (err) {
                        // This ensures that failing scenarios don't block the testing of other scenarios,
                        // even in sequential execution, giving the user as complete a picture of the molecule
                        // runs as possible.
                        failed_scenarios.add(scenario)
                        job.echo "Scenario failed with error: ${err}"
                    } finally {
                        // If failed_scenarios does not contain the current scenario, destroy
                        // If failed_scenarios does contain the current scenario and debug is false, destroy
                        // This takes advantage of logical 'or' short-circuiting:
                        // http://groovy-lang.org/operators.html#_short_circuiting
                        if (!failed_scenarios.contains(scenario) || !config.debug) {
                            job.dir(config.molecule_role_name) {
                                job.withEnv(scenario_env) {
                                    job.venvSh('.venv', ["molecule ${debug}destroy -s ${scenario}"])
                                }
                            }
                        }
                    }
                }
            }

            if (config.parallelize == true) {
                // parallel effectively wants a list of maps of ['label': closure]. Since we're running
                // collectEntries on molecule_scenarios, we write a transform closure to create that
                // list. In that transform, test_scenario is itself wrapped in a closure (yeah, again),
                // so that it isn't prematurely evaluated during collectEntries processing.
                job.parallel config.molecule_scenarios.collectEntries {scenario ->
                    ["Scenario ${scenario}": {test_scenario(scenario)}] // scenario scenario. scenario.
                }
            } else {
                // but for sequential testing, we can just call test_scenario for each scenario.
                for (scenario in config.molecule_scenarios) {
                   test_scenario(scenario)
               }
            }
            // Run the post-test hook before potentially erroring out for failed scenarios,
            // so that this hook is always called if the testing stage runs.
            postTestHook()
            if (failed_scenarios) {
                job.error("Molecule scenario(s) failed testing: " + failed_scenarios.join(', '))
            }
            updateGitlabStages Test: 'success'
        } catch (exc) {
            updateGitlabStages Test: 'failed'
            throw exc
        }
    }

    // hooks are closures declared at runtime by the calling user in the config mapping.
    // These methods are included here just to classify the intended invocation of them.
    def preSetUpHook() {
        return config.preSetUpHook(config)
    }

    def preCheckoutHook() {
        return config.preCheckoutHook(config)
    }

    def prePrepareHook() {
        return config.prePrepareHook(config)
    }

    def preTestHook() {
        return config.preTestHook(config)
    }

    def postTestHook() {
        return config.postTestHook(config)
    }

    def cleanupHook() {
        return config.cleanupHook(config)
    }
}

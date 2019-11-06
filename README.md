# OASIS Jenkinsfile Library

This is a [Pipeline Shared Library](https://jenkins.io/doc/book/pipeline/shared-libraries/)

Some functions defined in this shared library duplicate the functions available in the
[jenkinsfile-helpers](https://github.com/oasis-origin/jenkinsfile-helpers) repo. This library
expects to made available on a jenkins server with the name `oasis-pipeline`. Using any other name
will cause the library to fail.

```groovy
library('oasis-pipeline')

// pipeline stuff goes here
```

Note that this uses the dynamic-loading `library` syntax, not the `@Library` annotation syntax.
Either should work, but this makes it relatively easy to bring in multiple libraries if desired.

This library is expected to work whether it runs in a sandbox or not. To avoid potentially
seeing in-process script approvals of Jenkinsfiles, pipelines can be explicitly set to
run in a sandbox.

All vars exposed in this library should be available in the global scope.

## Installation

### Required Plugins

- [`pipeline`](https://plugins.jenkins.io/workflow-aggregator)
- [`git`](https://plugins.jenkins.io/git)
- [`ws-cleanup`](https://plugins.jenkins.io/ws-cleanup)

### Optional Plugins

- [`gitlab-plugin`](https://plugins.jenkins.io/gitlab-plugin) for gitlab merge request support
- [`github-branch-source`](https://plugins.jenkins.io/github-branch-source) for github pull request support

### Adding the library to Jenkins

Follow the instructions on [jenkins.io](https://jenkins.io/doc/book/pipeline/shared-libraries/#using-libraries),
using the following values:

- `Name`: oasis-pipeline
- `Default version`: master
- `Load implicitly`: Leave unchecked unless you know you need this
- `Retrieval method`: Modern SCM, using the unauthenticated https git link to this repositoryy

## Oasis Multistream Molecule Pipeline

A generic multistream pipeline is included, and contains logic for checking out, combining,
and testing projects with upstream and downstream components.

```groovy
library('oasis-pipeline')

oasisMultistreamMoleculePipeline {
    molecule_scenarios = ['default']
    upstream_git_url = 'https://github.com/namespace/project.git'
    downstream_git_url = 'https://downstream.git.provider/path/to/oasis-roles/project.git'
}
```

The expected structure for projects being tested in this way is that the upstream repository
is a "normal" molecule role (including origin projects, which may be doing playbook testing
via molecule). The downstream repository then contains only the downstream-specific bits as
a sort of overlay. Both repositories are then combined into a single directory, and all the
scenarios named in the `molecule_scenarios` array are tested.

If testinfra tests are used, those test results will be aggregated and stored by Jenkins
when testing is complete. In order for this to work, pytest must be configured to write out
its junit xml into the scenario directory in that scenario's molecule.yml:

```yaml
verifier:
  name: testinfra
  options:
    junit-xml: junit.xml
```

The role name under test is assumed to be the job's base name, so if the job is called
`rolename` in the `downstream-roles` Jenkins folder, the role's name will be `rolename`.
If you'd like to make a test job, create a new Jenkins folder to contain it rather than
naming the job something that will differ from what molecule expects the role name to be.

### Job configuration options

These are all the supported config variable you can use inside the
`oasisMultistreamMoleculePipeline`:

- `molecule_scenarios`: **Required** - An array of molecule scenarios to test.
- `molecule_role_name`: Name of the molecule role under test. Defaults to
  the job base name (`JOB_BASE_NAME` envvar in jenkins)
- `upstream_git_url`: URL of upstream git repo to clone and test.
- `upstream_git_branch`: Specific upstream branch to test. Defaults to `master`
- `downstream_git_url`: URL of downstream overlay git repo to clone and test.
  Set to `null` if the role under test has no downstream repository.
- `downstream_git_branch`: Specific downstream branch to test. Defaults to `master`
- `properties`: A list of job properties to add, corresponding to the `properties`
  step of a normal pipeline job. Defaults to empty list.
- `parallelize`: If `true`, parallelize scenario testing. Defaults to `false`.
- `debug`: If `true`, molecule will be run in debug mode, and instances will not be
  destroyed if a molecule scenario fails. Defaults to `false`. *You are responsible
  for cleaning up any resources left over after the run.*

### Debug Mode

In order to make it possible for job runners to more easily enable debug mode without
having to edit the Jenkinsfile, the pipeline also supports setting this value through
the use of a Boolean Parameter in Jenkins, named `OASIS_PIPELINE_DEBUG`.

If the parameter is used and `debug` is also included in the Jenkinsfile config body,
the parameter value will take precedence over the value specified in the config body.

### Hooks

Various hooks can be implemented to add functionality to the pipeline as-needed. Hooks are defined as
[closures](http://docs.groovy-lang.org/latest/html/documentation/index.html#_closures)
in the pipeline block. All hooks are passed the current job configuration map when the hook is called.

For example:

```groovy
library('oasis-pipeline')

oasisMultistreamMoleculePipeline {
    molecule_scenarios = ['default']
    upstream_git_url = 'https://github.com/oasis-roles/project.git'
    downstream_git_url = 'https://downstream.git.provider/path/to/oasis-roles/project.git'
    preSetUpHook = {config ->
        println config.molecule_scenarios
    }
}
```

If you don't want or need to access the config, the `config ->` preamble can be omitted.
However, even if the `config ->` preamble is omitted, it will still be available using
the closure's implicit `it` parameter. Hooks are normal Groovy Closures, so all normal
Closure behaviors are supported. Since hook closures are defined within the namespace
of the job definition, arbitrary job steps can also be run, as seen with the "println"
call in the example above.

#### Available Hooks

"pre" stage hooks:

- `preSetUpHook`: Run before any other steps in the "Set Up" stage. Ideal for making any
  configuration modifications before any other work is done.
- `preCheckoutHook`: Run before any other steps in the "Checkout" stage to allow checking out
  any additional repositories, or do other SCM work before upstream and downstream are checked out.
- `prePrepareHook`: Run before any other steps in the "Prepare" stage, used for any additional
  preparation needed as a result of any additions done in the `preCheckoutHook`.
- `preTestHook`: Run before any other steps in the "Test" stage, for any final preparation needed
  before the molecule test run starts.
- `preScenarioHook`: Run before each molecule scenario is tested. Takes two args in
  addition to `config`: `scenario` and `molecule`. `scenario` is the name of the scenario
  about to be tested, and `molecule` is a callable that will run molecule in the job environment
  with the given molecule args string. See example usage below.

"post" stage hooks:

- `postTestHook`: Run after all steps in the "Test" stage, used to clean up any testing resources
  or stash any artifacts generated during the test run. Can also be used to run additional tests
  after the normal molecule testing.

"cleanup" hook:

- `cleanupHook`: Runs unconditionally after every job run, regardless of job result,
  and unrelated to any stage.

#### preScenarioHook

All hooks receive the `config` dict as the first argument, but to facilitate customizing
specific scenario testing, the `preScenarioHook` also takes the scenario name, and a
`molecule` closure to facilitate running molecule subcommands targeting the current scenario.

Usage Example, to install scenario dependencies before a scenario is run:

```groovy
oasisMoleculePipeline {
    ...
    preScenarioHook = {config, scenario, molecule ->
        // Run an arbitrary job step
        println("Preinstalling dependencies for ${scenario}")

        // Run a molecule subcommand in the current molecule scenario environment:
        // This will run molecule in the current job's python virtualenv,
        // with the correct environment variables to ensure a molecule
        // ephermal dir that is used only by the current job and scenario.
        // The --debug flag will be added if config.debug is true.
        // This will expand to:
        //   "molecule dependency -s ${scenario}"
        molecule.call('dependency')
    }
}
```

Since hooks are passed the scenario name, it is possible to take specific
steps for specific scenarios, if desired:

```groovy
oasisMoleculePipeline {
    ...
    molecule_scenarios = ['first_scenario', 'second_scenario']
    // fail fast on lint and syntax check before running any tests
    // you could potentially omit lint and syntax from scenario sequences
    // by explicitly testing them on the first scenario
    preScenarioHook = {config, scenario, molecule ->
        if (scenario == config.molecule_scenarios.first()) {
            println("Running lint and syntax check for role")
            molecule.call('lint')
            molecule.call('syntax')
        }
    }
```

Note that the explicit `Closure.call` syntax isused with the molecule closure.
This is done to avoid a false-positive pipeline CPS method mismatch warning,
[as documented here](https://jenkins.io/redirect/pipeline-cps-method-mismatches/)
under "False Positives".

### Jenkinsfile Full Example

This example includes all configuration options with annotations in comments.

```groovy
library('oasis-pipeline')

oasisMultistreamMoleculePipeline {
    // Optional, defaults to ['default']. Must be an iterable of strings (String[])
    // Values here are the scenario name, as specified in molecule.yml
    molecule_scenarios = ['scenario_1', 'scenario_2']

    // This normally defaults to the job's "base name", which is the name of the job without
    // any folder names included. It needs to match the role name that molecule is expecting
    // to test, usually as defined in your converge playbook. If the job is named to match
    // the role name, this is not required.
    molecule_role_name = 'myrole'

    // By default, all molecule resources will be destroyed at the end of a test run.
    // Set this to true to prevent molecule resource destruction if the job is not
    // successful (any jenkins status other than "SUCCESS"). This should probably be used
    // in the case where tests pass when using molecule locally but fail in Jenkins, as
    // there's no simple way to clean up all the molecule resources once the job finishes.
    molecule_destroy_on_failure = true

    // These are required, and explicitly declare the the git url to use for the upstream
    // and downstream repositories. These are *repostitory* URLs, and should end in `.git`.
    // If you use the ssh transport, your jenkins server must already be configured for
    // ssh to work with the target host.
    upstream_git_url = 'https://github.com.com/namespace/project.git'
    downstream_git_url = 'https://downstream.git.provider/path/to/oasis-roles/project.git'

    // Similar to the *_git_url options, these allow you to explicitly declare branches
    // to test. These are probably only useful in debugging/development of Jenkinsfiles.
    // By default, they will get set to either master, or the branch containing a change
    // that triggers this job. The gitlab trigger and the github pull request builder
    // should both be used for automatic branch detection to work reliably. All
    // repositories are cloned with the default remote name of 'origin'.
    upstream_git_branch = 'origin/upstream-test-branch'
    downstream_git_branch = 'origin/downstream-test-branch'

    // If true, will run scenario tests in parallel stages. As the number of scenarios
    // increases, so do the odds of running into resource/quota limitations. Use with care.
    parallelize = false

    // All the hooks, declared here as empty closures.
    // These aren't necessary in any Jenkinsfile; you can implement any of the hooks,
    // or none of the hooks, as you see fit.
    preSetUpHook = { config -> }
    preCheckoutHook = { config -> }
    prePrepareHook = { config -> }
    preTestHook = { config -> }
    preScenarioHook = { config, scenario, molecule -> }
    postTestHook = { config -> }
    postCheckoutHook = { config -> }
    cleanupHook = { config -> }
}
```

## Oasis Molecule Pipeline (single stream)

In addition to `oasisMultistreamMoleculePipeline`, the `oasisMoleculePipeline` global
is also exposed with exactly the same functionality, but `downstream_git_url` is forced
to null, eliminating any interaction with an overlay coming from a second git repo.

In this mode, the stream used is expected to be upstream, so only `upstream_git_url`
is used.

## Utility Functions

Utility functions can be accessed via the `oasis` global variable. See [oasis.groovy](vars/oasis.groovy)
for a complete view of all of the functions available there. Some of the more generally useful
functions are outlined here.

### configFromBody()

Reusable implementation of the Jenkinsfile "builder pattern":

```groovy
def config = [:]
body.resolveStrategy = Closure.DELEGATE_FIRST
body.delegate = config
body()
return config
```

# Notes and Warning

## Unstable API

Commits to this library should be entirely self-consistent, but no guarantees are made to the
consistency of this library's API to other callers. Objects may move or disappear at any time,
though care is taken to limit breaking changes and the likelihood of such changes occurring
will decrease as the library matures.

## Non-native speakers

This library is for testing Ansible (written in Python) roles, using the molecule (written
in Python) framework. As such, it is written and maintained by people that primarily write
Python, and questionable Groovy may result. Apologies in advance, and pull requests welcome.

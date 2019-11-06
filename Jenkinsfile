/*
Since the pipeline itself is being tested here,
inspect the environment to know which branch of
the pipeline to check out and test. Eventually,
it would be nice to have an actual groovy unit
test suite.
*/
def pipeline_branch = env.CHANGE_ID ? "pr/${env.CHANGE_ID}" : "master"

library("oasis-pipeline@origin/${pipeline_branch}")

oasisMoleculePipeline {
  // can test with any role that has openstack scenarios
  upstream_git_url = 'https://github.com/oasis-roles/molecule_openstack_ci.git'
  upstream_git_branch = 'master'
  molecule_role_name = 'molecule_openstack_ci'
  properties = [pipelineTriggers([cron('H H * * *')])]

  // quick check to make sure the vars work for this hook,
  // since it's the abnormal one
  preScenarioHook = {config, scenario, molecule ->
    println(scenario)
    molecule.call('lint')
  }

  // also test a "normal" hook, which helps prove that all hooks are fine, since
  // all hooks (except preScenarioHook) are defined and called the same way.
  preCheckoutHook = {config ->
    if (config.molecule_role_name != 'molecule_openstack_ci') {
        error('hook was not properly passed a config mapping')
    }
  }
}

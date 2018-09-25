import com.redhat.oasis.MultistreamMoleculePipeline

// Exposed as a global that takes a body block as a config, sends that config to the
// pipeline class, and kicks off a default run. Uses the postTestHook to record results.
// The testing "API" is pretty simple: Any files named "junit.xml" in the workspace will
// be parsed by jenkins and included in the test results.
def call(Closure body) {
    // oasis-pipeline-specific configuration setup:
    def config = oasis.configFromBody(body)
    config.postTestHook = {
        junit allowEmptyResults: true, keepLongStdio: true, testResults: '**/junit.xml'
    }

    node {
        def pipeline = new MultistreamMoleculePipeline(this, config)
        pipeline.runDefault()
    }
}

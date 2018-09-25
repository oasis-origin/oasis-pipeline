import com.redhat.oasis.MultistreamMoleculePipeline

// Copy of oasisMultistreamMoleculePipeline, but downstream_git_url is forced to null,
// disabling the multistream capabilities. This exists solely because setting the
// downstream_git_url to null looks silly in the very common case where multistream
// testing is neither needed nor desired.
def call(Closure body) {
    // oasis-pipeline-specific configuration setup:
    def config = oasis.configFromBody(body)
    config.downstream_git_url = null
    config.postTestHook = {
        junit allowEmptyResults: true, keepLongStdio: true, testResults: '**/junit.xml'
    }

    node {
        def pipeline = new MultistreamMoleculePipeline(this, config)
        pipeline.runDefault()
    }
}

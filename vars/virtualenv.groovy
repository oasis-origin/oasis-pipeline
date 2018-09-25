def call(String name, List packages=[]) {
	def path = "${WORKSPACE}/${name}"
	if ( path.contains(" ") ) {
		throw new Exception("Path cannot include a space")
	}
	// Ensure we hvae a fresh path
	sh "rm -rf '${path}'"
	sh """virtualenv --no-setuptools --system-site-packages '${path}'
	      . '${path}/bin/activate'
	      curl https://bootstrap.pypa.io/get-pip.py | python
	      '${path}/bin/python' '${path}/bin/pip' install -U setuptools
    """
    if ( packages ) {
	    sh "'${path}/bin/python' '${path}/bin/pip' install ${packages.join(' ')}"
    }
}

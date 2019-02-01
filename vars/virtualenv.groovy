def call(String name, List packages=[], Boolean system_site_package=false) {
	// --system-site-packages injection string
	def ssp_str = ""
	def path = "${WORKSPACE}/${name}"
	if ( path.contains(" ") ) {
		throw new Exception("Path cannot include a space")
	}

	// Handle the addition of system-site-packages if requested
	if ( system_site_packages ) {
		ssp_str = "--system-site-packages"
	}

	// Ensure we have a fresh path
	sh "rm -rf '${path}'"

	// setup the virtualenv, and then install the latest pip and setuptools
	sh """virtualenv --no-setuptools ${ssp_str} '${path}'
		  . '${path}/bin/activate'
		  curl https://bootstrap.pypa.io/get-pip.py | python
		  '${path}/bin/python' '${path}/bin/pip' install -U setuptools
	"""

	if ( packages ) {
		sh "'${path}/bin/python' '${path}/bin/pip' install ${packages.join(' ')}"
	}
}

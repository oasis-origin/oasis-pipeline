def call(String name, List commands=[]) {
	def path = "${WORKSPACE}/${name}"
	sh """. '${path}/bin/activate'
	      ${commands.join('\n')}"""
}

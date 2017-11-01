package com.lazerycode.jmeter.mojo;

import static com.lazerycode.jmeter.properties.ConfigurationFiles.GLOBAL_PROPERTIES;
import static com.lazerycode.jmeter.properties.ConfigurationFiles.JMETER_PROPERTIES;
import static com.lazerycode.jmeter.properties.ConfigurationFiles.REPORT_GENERATOR_PROPERTIES;
import static com.lazerycode.jmeter.properties.ConfigurationFiles.SAVE_SERVICE_PROPERTIES;
import static com.lazerycode.jmeter.properties.ConfigurationFiles.SYSTEM_PROPERTIES;
import static com.lazerycode.jmeter.properties.ConfigurationFiles.UPGRADE_PROPERTIES;
import static com.lazerycode.jmeter.properties.ConfigurationFiles.USER_PROPERTIES;
import static com.lazerycode.jmeter.properties.ConfigurationFiles.values;
import static org.apache.commons.io.FileUtils.copyInputStreamToFile;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyFilter;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.graph.Exclusion;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactDescriptorException;
import org.eclipse.aether.resolution.ArtifactDescriptorRequest;
import org.eclipse.aether.resolution.ArtifactDescriptorResult;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.util.artifact.JavaScopes;
import org.eclipse.aether.util.filter.DependencyFilterUtils;
import org.eclipse.aether.util.version.GenericVersionScheme;
import org.eclipse.aether.version.InvalidVersionSpecificationException;
import org.eclipse.aether.version.Version;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.lazerycode.jmeter.exceptions.DependencyResolutionException;
import com.lazerycode.jmeter.exceptions.IOException;
import com.lazerycode.jmeter.json.TestConfig;
import com.lazerycode.jmeter.properties.ConfigurationFiles;
import com.lazerycode.jmeter.properties.PropertiesFile;
import com.lazerycode.jmeter.properties.PropertiesMapping;

@Mojo(name = "configure", defaultPhase = LifecyclePhase.COMPILE)
public class ConfigureJMeterMojo extends AbstractJMeterMojo {

	@Component
	private RepositorySystem repositorySystem;

	@Parameter(defaultValue = "${repositorySystemSession}", readonly = true)
	private RepositorySystemSession repositorySystemSession;

	@Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true)
	private List<RemoteRepository> repositoryList;

	/**
	 * Name of the base config json file
	 */
	private static final String baseConfigFile = "/config.json";

	/**
	 * The version of JMeter that this plugin will use to run tests.
	 * We use a hard coded list of artifacts to configure JMeter locally,
	 * if you change this version number the list of artifacts required to run JMeter may change.
	 * If this happens you will need to override the &lt;jmeterArtifacts&gt; element.
	 */
	@Parameter(defaultValue = "3.3")
	private String jmeterVersion;

	/**
	 * A list of artifacts that we use to configure JMeter.
	 * This list is hard coded by default, you can override this list and supply your own list of artifacts for JMeter.
	 * This would be useful if you want to use a different version of JMeter that has a different list of required artifacts.
	 * <p/>
	 * &lt;jmeterExtensions&gt;
	 * &nbsp;&nbsp;&lt;artifact&gt;kg.apc:jmeter-plugins:1.3.1&lt;/artifact&gt;
	 * &lt;jmeterExtensions&gt;
	 */
	@Parameter
	private List<String> jmeterArtifacts = new ArrayList<>();

	/**
	 * A list of artifacts that the plugin should ignore.
	 * This would be useful if you don't want specific dependencies brought down by JMeter (or any used defined artifacts) copied into the JMeter directory structure.
	 * <p/>
	 * &lt;ignoredArtifacts&gt;
	 * &nbsp;&nbsp;&lt;artifact&gt;org.bouncycastle:bcprov-jdk15on:1.49&lt;/artifact&gt;
	 * &lt;ignoredArtifacts&gt;
	 */
	@Parameter
	private List<String> ignoredArtifacts = new ArrayList<>();

	/**
	 * Download all dependencies of files you want to add to lib/ext and copy them to lib/ext too
	 * <p/>
	 * &lt;downloadExtensionDependencies&gt;
	 * &nbsp;&nbsp;&lt;true&gt;
	 * &lt;downloadExtensionDependencies&gt;
	 */
	@Parameter(defaultValue = "true")
	protected boolean downloadExtensionDependencies;

	/**
	 * A list of artifacts that should be copied into the lib/ext directory e.g.
	 * <p/>
	 * &lt;jmeterExtensions&gt;
	 * &nbsp;&nbsp;&lt;artifact&gt;kg.apc:jmeter-plugins:1.3.1&lt;/artifact&gt;
	 * &lt;jmeterExtensions&gt;
	 */
	@Parameter
	protected List<String> jmeterExtensions = new ArrayList<>();

	/**
	 * Download all transitive dependencies of the JMeter artifacts.
	 * <p/>
	 * &lt;downloadJMeterDependencies&gt;
	 * &nbsp;&nbsp;&lt;false&gt;
	 * &lt;downloadJMeterDependencies&gt;
	 */
	@Parameter(defaultValue = "false")
	protected boolean downloadJMeterDependencies;

	/**
	 * Download all optional transitive dependencies of artifacts.
	 * <p/>
	 * &lt;downloadOptionalDependencies&gt;
	 * &nbsp;&nbsp;&lt;true&gt;
	 * &lt;downloadOptionalDependencies&gt;
	 */
	@Parameter(defaultValue = "false")
	protected boolean downloadOptionalDependencies;

	/**
	 * Download all dependencies of files you want to add to lib/junit and copy them to lib/junit too
	 * <p/>
	 * &lt;downloadLibraryDependencies&gt;
	 * &nbsp;&nbsp;&lt;true&gt;
	 * &lt;downloadLibraryDependencies&gt;
	 */
	@Parameter(defaultValue = "true")
	protected boolean downloadLibraryDependencies;

	/**
	 * A list of artifacts that should be copied into the lib/junit directory e.g.
	 * <p/>
	 * &lt;junitLibraries&gt;
	 * &nbsp;&nbsp;&lt;artifact&gt;com.lazerycode.junit:junit-test:1.0.0&lt;/artifact&gt;
	 * &lt;junitLibraries&gt;
	 */
	@Parameter
	protected List<String> junitLibraries = new ArrayList<>();

	/**
	 * Absolute path to JMeter custom (test dependent) properties file.
	 */
	@Parameter
	protected Map<String, String> propertiesJMeter = new HashMap<>();

	/**
	 * JMeter Properties that are merged with precedence into default JMeter file in saveservice.properties
	 */
	@Parameter
	protected Map<String, String> propertiesSaveService = new HashMap<>();

	/**
	 * JMeter Properties that are merged with precedence into default JMeter file in reportgenerator.properties
	 */
	@Parameter
	protected Map<String, String> propertiesReportGenerator = new HashMap<>();

	/**
	 * JMeter Properties that are merged with precedence into default JMeter file in upgrade.properties
	 */
	@Parameter
	protected Map<String, String> propertiesUpgrade = new HashMap<>();

	/**
	 * JMeter Properties that are merged with precedence into default JMeter file in user.properties
	 * user.properties takes precedence over jmeter.properties
	 */
	@Parameter
	protected Map<String, String> propertiesUser = new HashMap<>();

	/**
	 * JMeter Global Properties that override those given in jmeterProps. <br>
	 * This sets local and remote properties (JMeter's definition of global properties is actually remote properties)
	 * and overrides any local/remote properties already set
	 */
	@Parameter
	protected Map<String, String> propertiesGlobal = new HashMap<>();

	/**
	 * (Java) System properties set for the test run.
	 * Properties are merged with precedence into default JMeter file system.properties
	 */
	@Parameter
	protected Map<String, String> propertiesSystem = new HashMap<>();

	/**
	 * Path under which .properties files are stored.
	 */
	@Parameter(defaultValue = "${basedir}/src/test/jmeter")
	protected File propertiesFilesDirectory;

	/**
	 * Replace the default JMeter properties with any custom properties files supplied.
	 * (If set to false any custom properties files will be merged with the default JMeter properties files, custom properties will overwrite default ones)
	 */
	@Parameter(defaultValue = "true")
	protected boolean propertiesReplacedByCustomFiles;

//	TODO move customPropertiesFiles here;

	/**
	 * Set the format of the results generated by JMeter
	 * Valid values are: xml, csv (XML set by default).
	 */
	@Parameter(defaultValue = "xml")
	protected String resultsFileFormat;
	protected boolean resultsOutputIsCSVFormat = false;

	public static final String JMETER_CONFIG_ARTIFACT_NAME = "ApacheJMeter_config";
	private static final String JMETER_GROUP_ID = "org.apache.jmeter";
	protected static Artifact jmeterConfigArtifact;
	protected static File customPropertiesDirectory;
	protected static File libDirectory;
	protected static File libExtDirectory;
	protected static File libJUnitDirectory;

	/**
	 * Configure a local instance of JMeter
	 *
	 * @throws MojoExecutionException
	 * @throws MojoFailureException
	 */
	@Override
	public void doExecute() throws MojoExecutionException, MojoFailureException {
		getLog().info("-------------------------------------------------------");
		getLog().info(" Configuring JMeter...");
		getLog().info("-------------------------------------------------------");
		generateJMeterDirectoryTree();
		configureJMeterArtifacts();
		populateJMeterDirectoryTree();
		copyExplicitLibraries(jmeterExtensions, libExtDirectory, downloadExtensionDependencies);
		copyExplicitLibraries(junitLibraries, libJUnitDirectory, downloadLibraryDependencies);
		configurePropertiesFiles();
		generateTestConfig();
	}

	/**
	 * Generate the directory tree utilised by JMeter.
	 */
	protected void generateJMeterDirectoryTree() {
		workingDirectory = new File(jmeterDirectory, "bin");
		workingDirectory.mkdirs(); //TODO remove this, it's covered in extractConfigSettings()
		customPropertiesDirectory = new File(jmeterDirectory, "custom_properties");
		customPropertiesDirectory.mkdirs();
		libDirectory = new File(jmeterDirectory, "lib");
		libExtDirectory = new File(libDirectory, "ext");
		libExtDirectory.mkdirs();
		libJUnitDirectory = new File(libDirectory, "junit");
		libJUnitDirectory.mkdirs();
		testFilesBuildDirectory.mkdirs();
		resultsDirectory.mkdirs();
		if(generateReports) {
		    reportDirectory.mkdirs();
		}
		logsDirectory.mkdirs();
	}

	protected void configurePropertiesFiles() throws MojoExecutionException, MojoFailureException {
		propertiesMap.put(JMETER_PROPERTIES, new PropertiesMapping(propertiesJMeter));
		propertiesMap.put(SAVE_SERVICE_PROPERTIES, new PropertiesMapping(propertiesSaveService));
		propertiesMap.put(UPGRADE_PROPERTIES, new PropertiesMapping(propertiesUpgrade));
		propertiesMap.put(SYSTEM_PROPERTIES, new PropertiesMapping(propertiesSystem));
		propertiesMap.put(REPORT_GENERATOR_PROPERTIES, new PropertiesMapping(propertiesReportGenerator));
		propertiesMap.put(USER_PROPERTIES, new PropertiesMapping(propertiesUser));
		propertiesMap.put(GLOBAL_PROPERTIES, new PropertiesMapping(propertiesGlobal));

		setJMeterResultFileFormat();

		for (ConfigurationFiles configurationFile : values()) {
			File suppliedPropertiesFile = new File(propertiesFilesDirectory, configurationFile.getFilename());
			File propertiesFileToWrite = new File(workingDirectory, configurationFile.getFilename());

			PropertiesFile somePropertiesFile = new PropertiesFile(jmeterConfigArtifact, configurationFile);
			somePropertiesFile.loadProvidedPropertiesIfAvailable(suppliedPropertiesFile, propertiesReplacedByCustomFiles);
			somePropertiesFile.addAndOverwriteProperties(propertiesMap.get(configurationFile).getAdditionalProperties());
			somePropertiesFile.writePropertiesToFile(propertiesFileToWrite);

			propertiesMap.get(configurationFile).setPropertiesFile(somePropertiesFile);
		}

		for (File customPropertiesFile : customPropertiesFiles) {
			PropertiesFile customProperties = new PropertiesFile(customPropertiesFile);
			String customPropertiesFilename = 
			        FilenameUtils.getBaseName(customPropertiesFile.getName()) 
			        + "-" + UUID.randomUUID().toString() 
			        + "." + FilenameUtils.getExtension(customPropertiesFile.getName());
			customProperties.writePropertiesToFile(new File(customPropertiesDirectory, customPropertiesFilename));
		}

		setDefaultPluginProperties(workingDirectory.getAbsolutePath());
	}

	protected void generateTestConfig() throws MojoExecutionException {
	    try (InputStream configFile = this.getClass().getResourceAsStream(baseConfigFile)) {
    		TestConfig testConfig = new TestConfig(configFile);
    		testConfig.setResultsOutputIsCSVFormat(resultsOutputIsCSVFormat);
    		testConfig.setGenerateReports(generateReports);
    		testConfig.writeResultFilesConfigTo(testConfigFile);
	    } catch(java.io.IOException ex) {
	        throw new MojoExecutionException("Exception creating TestConfig", ex);
	    }
	}

	protected void setJMeterResultFileFormat() {
		if (generateReports || "csv".equalsIgnoreCase(resultsFileFormat)) {
			propertiesJMeter.put("jmeter.save.saveservice.output_format", "csv");
			resultsOutputIsCSVFormat = true;
		} else {
			propertiesJMeter.put("jmeter.save.saveservice.output_format", "xml");
			resultsOutputIsCSVFormat = false;
		}
	}


	public void setDefaultPluginProperties(String userDirectory) {
		//JMeter uses the system property "user.dir" to set its base working directory
		System.setProperty("user.dir", userDirectory);
		//Prevent JMeter from throwing some System.exit() calls
		System.setProperty("jmeterengine.remote.system.exit", "false");
		System.setProperty("jmeterengine.stopfail.system.exit", "false");
	}

	/**
	 * This sets the default list of artifacts that we use to set up a local instance of JMeter.
	 * We only use this default list if &lt;jmeterArtifacts&gt; has not been overridden in the POM.
	 */
	private void configureJMeterArtifacts() {
		if (jmeterArtifacts.isEmpty()) {
			jmeterArtifacts.add(JMETER_GROUP_ID + ":ApacheJMeter:" + jmeterVersion);
			jmeterArtifacts.add(JMETER_GROUP_ID + ":ApacheJMeter_components:" + jmeterVersion);
			jmeterArtifacts.add(JMETER_GROUP_ID + ":ApacheJMeter_config:" + jmeterVersion);
			jmeterArtifacts.add(JMETER_GROUP_ID + ":ApacheJMeter_core:" + jmeterVersion);
			jmeterArtifacts.add(JMETER_GROUP_ID + ":ApacheJMeter_ftp:" + jmeterVersion);
			jmeterArtifacts.add(JMETER_GROUP_ID + ":ApacheJMeter_functions:" + jmeterVersion);
			jmeterArtifacts.add(JMETER_GROUP_ID + ":ApacheJMeter_http:" + jmeterVersion);
			jmeterArtifacts.add(JMETER_GROUP_ID + ":ApacheJMeter_java:" + jmeterVersion);
			jmeterArtifacts.add(JMETER_GROUP_ID + ":ApacheJMeter_jdbc:" + jmeterVersion);
			jmeterArtifacts.add(JMETER_GROUP_ID + ":ApacheJMeter_jms:" + jmeterVersion);
			jmeterArtifacts.add(JMETER_GROUP_ID + ":ApacheJMeter_junit:" + jmeterVersion);
			jmeterArtifacts.add(JMETER_GROUP_ID + ":ApacheJMeter_ldap:" + jmeterVersion);
			jmeterArtifacts.add(JMETER_GROUP_ID + ":ApacheJMeter_mail:" + jmeterVersion);
			jmeterArtifacts.add(JMETER_GROUP_ID + ":ApacheJMeter_mongodb:" + jmeterVersion);
			jmeterArtifacts.add(JMETER_GROUP_ID + ":ApacheJMeter_native:" + jmeterVersion);
			jmeterArtifacts.add(JMETER_GROUP_ID + ":ApacheJMeter_tcp:" + jmeterVersion);
			jmeterArtifacts.add(JMETER_GROUP_ID + ":jorphan:" + jmeterVersion);						//TODO move to lib dir
		}
	}

	private void populateJMeterDirectoryTree() throws DependencyResolutionException, IOException {
		if (jmeterArtifacts.isEmpty()) {
			throw new DependencyResolutionException("No JMeter dependencies specified!, check jmeterArtifacts and jmeterVersion elements");
		}
		for (String desiredArtifact : jmeterArtifacts) {
			Artifact returnedArtifact = getArtifactResult(new DefaultArtifact(desiredArtifact));
			switch (returnedArtifact.getArtifactId()) {
				case JMETER_CONFIG_ARTIFACT_NAME:
					jmeterConfigArtifact = returnedArtifact;
					//TODO Could move the below elsewhere if required.
					extractConfigSettings(jmeterConfigArtifact);
					break;
				case "ApacheJMeter":
					runtimeJarName = returnedArtifact.getFile().getName();
					copyArtifact(returnedArtifact, workingDirectory);
					copyTransitiveRuntimeDependenciesToLibDirectory(returnedArtifact, downloadJMeterDependencies);
					break;
				default:
					copyArtifact(returnedArtifact, libExtDirectory);
					copyTransitiveRuntimeDependenciesToLibDirectory(returnedArtifact, downloadJMeterDependencies);
			}
		}

		if (confFilesDirectory.exists()) {
			copyFilesInTestDirectory(confFilesDirectory, new File(jmeterDirectory, "bin"));
		}
	}

	/**
	 * Copy a list of libraries to a specific folder.
	 *
	 * @param desiredArtifacts A list of artifacts
	 * @param destination      A destination folder to copy these artifacts to
	 * @throws DependencyResolutionException
	 * @throws IOException
	 */
	private void copyExplicitLibraries(List<String> desiredArtifacts, File destination, boolean downloadDependencies) throws DependencyResolutionException, IOException {
		for (String desiredArtifact : desiredArtifacts) {
			copyExplicitLibraries(desiredArtifact, destination, downloadDependencies);
		}
	}
	
	private void copyExplicitLibraries(String desiredArtifact, File destination, boolean downloadDependencies) throws DependencyResolutionException, IOException {
		Artifact returnedArtifact = getArtifactResult(new DefaultArtifact(desiredArtifact));
		copyArtifact(returnedArtifact, destination);
		if (downloadDependencies) {
			//copyTransitiveRuntimeDependenciesToLibDirectory(returnedArtifact, true);
			resolveTestDepsAndCopyWithTransitivity(returnedArtifact, true);
		}
		// Помним, что наши -ui-tests проекты зависят от ui-проектов(в реальности у тестов в момент выполнения на classpath есть весь src/main/), 
		// но эта зависимость неявная, в pom`е не описана. Чтобы в конфигурации плагина не указывать проекты дважды, подсуетимся здесь. 
		// Также, x-ui-проект обычно зависит от x-проекта. Эта зависимость вынесена в профили, которые активируются по наличию файлов в ФС.
		// Ессно наш плагин такое не заметит. Итого, на каждый -ui-tests проект надо вспомнить еще как минимум про два. Например, для rms-ui-tests
		// нужно еще процессить rms-ui и rms.
		if(returnedArtifact.getGroupId().startsWith("ru.argustelecom.") && returnedArtifact.getArtifactId().endsWith("-ui")){
			// Ожидаемый формат  <groupId>:<artifactId>[:<extension>[:<classifier>]]:<version>. @see DefaultArtifact( String coords )
			String coordinates = "";
			if (returnedArtifact.getClassifier().equals("tests")){
				coordinates = returnedArtifact.getGroupId() + ":" +  returnedArtifact.getArtifactId() + ":" + returnedArtifact.getVersion();
			} else {
				String artifactId = returnedArtifact.getArtifactId().substring(0, returnedArtifact.getArtifactId().length() - "-ui".length());
				coordinates = returnedArtifact.getGroupId() + ":" + artifactId + (Strings.isNullOrEmpty(returnedArtifact.getClassifier()) ? ""
								: ":" + returnedArtifact.getExtension() + ":" + returnedArtifact.getClassifier()) + ":" + returnedArtifact.getVersion();
			}
			copyExplicitLibraries(coordinates, destination, downloadDependencies);
		}
		
		
	}
	
	/**
	 * Dependency graph can contain circular references. For example: dom4j:dom4j:jar:1.5.2 and jaxen:jaxen:jar:1.1-beta-4
	 * To prevent endless loop and stack overflow, save processed artifacts and check not processed previously before processing.
	 * <p>
	 * May be better to use {@link Artifact}, but {@link AbstractArtifact#equals} unreliably depends on local file path. So using {@link Exclusion} items
	 * <p> 
	 */
	private Set<Exclusion> processedArtifacts = new HashSet<Exclusion> (); 
	
	/**
	 * После наших переделок в каталоге lib множество артефактов с одинаковым groupid, classifier и artifactId, но разными версиями.
	 * Ессно, это все портит, так как дубли на classpath при открытии jmeter. Причина, скорее всего, в  том, что резолвим артефакты поштучно
	 * в copyTransitiveRuntimeDependenciesToLibDirectory. Будем исключать дубли в последний момент, на этапе копирования. #TODO: криво
	 * <p>
	 * 
	 */
	private Set<Artifact> copiedArtifacts = new HashSet<Artifact> ();

	
	/**
	 * Find a specific artifact in a remote repository
	 *
	 * @param desiredArtifact The artifact that we want to find
	 * @return Will return an ArtifactResult object
	 * @throws DependencyResolutionException
	 */
	private Artifact getArtifactResult(Artifact desiredArtifact) throws DependencyResolutionException {
		ArtifactRequest artifactRequest = new ArtifactRequest();
		artifactRequest.setArtifact(desiredArtifact);
		artifactRequest.setRepositories(repositoryList);
		try {
			return repositorySystem.resolveArtifact(repositorySystemSession, artifactRequest).getArtifact();
		} catch (ArtifactResolutionException e) {
			throw new DependencyResolutionException(e.getMessage(), e);
		}
	}
	
	
	private void resolveTestDepsAndCopyWithTransitivity(Artifact artifact, boolean getDependenciesOfDependency) throws DependencyResolutionException, IOException {
		ArtifactDescriptorRequest request = new ArtifactDescriptorRequest(artifact, repositoryList, null);
		try {
			ArtifactDescriptorResult result = repositorySystem.readArtifactDescriptor(repositorySystemSession, request);
			for (Dependency dep: result.getDependencies()){
				// Здесь не можем отсеивать зависимости по scope. 
				// нужно использовать зависимости с любым scope, так как для выполнения тестов нужны и test, и provided и тем более compile-scoped зависимости  
				if( true /*(dep.getScope().equals(JavaScopes.TEST)) || (dep.getScope().equals(JavaScopes.COMPILE))*/){
					ArtifactResult artifactResult = repositorySystem.resolveArtifact(repositorySystemSession, new ArtifactRequest(dep.getArtifact(), repositoryList, null));
					if(isLibraryArtifact(artifactResult.getArtifact())){
						copyArtifact(artifactResult.getArtifact(), libDirectory);
					}
					copyTransitiveRuntimeDependenciesToLibDirectory(dep, getDependenciesOfDependency);
				}
			}
		} catch (ArtifactDescriptorException | ArtifactResolutionException e) {
			e.printStackTrace();
			throw new DependencyResolutionException(e.getMessage(), e);
		}	
	}

	/**
	 * Collate a list of transitive runtime dependencies that need to be copied to the /lib directory and then copy them there.
	 *
	 * @param artifact The artifact that is a transitive dependency
	 * @throws DependencyResolutionException
	 * @throws IOException
	 */
	private void copyTransitiveRuntimeDependenciesToLibDirectory(Artifact artifact, boolean getDependenciesOfDependency) throws DependencyResolutionException, IOException {
		copyTransitiveRuntimeDependenciesToLibDirectory(new Dependency(artifact, JavaScopes.TEST), getDependenciesOfDependency); 
	}
	
	
	/**
	 * Same as {@link #copyTransitiveRuntimeDependenciesToLibDirectory(Artifact, boolean)} but with respect to dependency excludes
	 * <p>
	 * 
	 * @param rootDependency
	 * @param getDependenciesOfDependency
	 * @throws DependencyResolutionException
	 * @throws IOException
	 */
	private void copyTransitiveRuntimeDependenciesToLibDirectory(Dependency rootDependency, boolean getDependenciesOfDependency) throws DependencyResolutionException, IOException {
		CollectRequest collectRequest = new CollectRequest();
		collectRequest.setRoot(rootDependency);
		collectRequest.setRepositories(repositoryList);
		// в #classpathFilter передаем на самом деле не scope, а идентификатор classpath(просто используется тот же enum, что и для scope`ов).
		//То есть, например, для тестового classpath нужны зависимости с любым scope (то есть фильтр по TEST наиболее мягкий)
		DependencyFilter dependencyFilter = DependencyFilterUtils.classpathFilter(JavaScopes.TEST);
		DependencyRequest dependencyRequest = new DependencyRequest(collectRequest, dependencyFilter);
		
		if (getLog().isDebugEnabled()) {
			getLog().debug("Root dependency name: " + rootDependency.toString());
			if ((dependencyRequest.getCollectRequest() != null) && (dependencyRequest.getCollectRequest().getTrace() != null)){
				getLog().debug("Root dependency request trace: " + dependencyRequest.getCollectRequest().getTrace().toString());
			}
			getLog().debug("Root dependency exclusions: " + rootDependency.getExclusions());
			getLog().debug("-------------------------------------------------------");
		}

		try {
			// здесь не можем резолвить, так как могут попасться exclusions, которые потому и excluded, что отсутствуют в репозиториях.
			//List<DependencyNode> artifactDependencyNodes = repositorySystem.resolveDependencies(repositorySystemSession, dependencyRequest).getRoot().getChildren();
			List<DependencyNode> artifactDependencyNodes = repositorySystem.collectDependencies(repositorySystemSession, collectRequest).getRoot().getChildren();
			for (DependencyNode dependencyNode : artifactDependencyNodes) {
				if (getLog().isDebugEnabled()) {
					getLog().debug("Dependency name: " + dependencyNode.toString());
					getLog().debug("-------------------------------------------------------");
				}
				Exclusion dummyExclusion = new Exclusion(dependencyNode.getArtifact().getGroupId(), dependencyNode.getArtifact().getArtifactId(), 
						dependencyNode.getArtifact().getClassifier(), dependencyNode.getArtifact().getExtension());
				if ((downloadOptionalDependencies || !dependencyNode.getDependency().isOptional()) &&
						!((rootDependency.getExclusions() != null) && (containsEx(rootDependency.getExclusions(), dummyExclusion)) ) ) {
					//Artifact returnedArtifact = getArtifactResult(dependencyNode.getArtifact());
					Artifact returnedArtifact = repositorySystem.resolveArtifact(repositorySystemSession, new ArtifactRequest(dependencyNode)).getArtifact();
					if ((!returnedArtifact.getArtifactId().startsWith("ApacheJMeter_")) && (isLibraryArtifact(returnedArtifact))){
						copyArtifact(returnedArtifact, libDirectory);
					}

					if (getDependenciesOfDependency && !processedArtifacts.contains(dummyExclusion)) {
						processedArtifacts.add(dummyExclusion);
						if (getLog().isDebugEnabled()) {
							getLog().debug("Added to processed list: " + dummyExclusion);
							getLog().debug("total processed: " + processedArtifacts.size());
							getLog().debug("-------------------------------------------------------");
						}
						copyTransitiveRuntimeDependenciesToLibDirectory(dependencyNode.getDependency(), true);
						
						
					}
				}
			}
		} catch (DependencyCollectionException | ArtifactResolutionException e) {
			throw new DependencyResolutionException(e.getMessage(), e);
		}
		
	}
	
	/**
	 * Эксклюд может быть указан wildcard`ом:
	 * -- groupId:artifactId:*:*
	 * -- groupId:*:*:*
	 * <p>
	 * Да и вообще, требовать строгого совпадения вплоть до версии и classifier не нужно
	 * <p>
	 * #TODO: правильныее было бы переписать {@link Exclusion#equals(Object)}, но как быть с граничным случаем: 
	 * Если  contains(id1:*:*:*, id1:id2:*:*)==true, то вот equals??    
	 * #TODO: наверняка есть утилитарный код в Aether или мавене на эту тему
	 * <p>
	 * @param exclusions
	 * @param exclusion
	 * @return
	 */
	private boolean containsEx(Collection<Exclusion> exclusions, Exclusion exclusion){
		Preconditions.checkState(exclusions != null);
		Preconditions.checkState(exclusion != null);
		for(Exclusion x: exclusions){
			if (x.getGroupId().equals(exclusion.getGroupId())){
				if( (x.getArtifactId().equals(exclusion.getArtifactId())) || (x.getArtifactId().equals("*"))){
					return true;
				}
			}
		}
		return false;
	}
	
	private boolean isLibraryArtifact(Artifact artifact){
		return artifact.getExtension().equals("jar") || artifact.getExtension().equals("war") || artifact.getExtension().equals("zip") || artifact.getExtension().equals("ear");		
	}
	

	/**
	 * Copy an Artifact to a directory
	 *
	 * @param artifact             Artifact that needs to be copied.
	 * @param destinationDirectory Directory to copy the artifact to.
	 * @throws IOException                   Unable to copy file
	 * @throws DependencyResolutionException Unable to resolve dependency
	 */
	private void copyArtifact(Artifact artifact, File destinationDirectory) throws IOException, DependencyResolutionException {
		for (String ignoredArtifact : ignoredArtifacts) {
			Artifact artifactToIgnore = getArtifactResult(new DefaultArtifact(ignoredArtifact));
			if (artifact.getFile().getName().equals(artifactToIgnore.getFile().getName())) {
				getLog().debug(artifact.getFile().getName() + " has not been copied over because it is in the ignore list.");
				return;
			}
		}
		try {
			for (Artifact x: copiedArtifacts){
				if(x.getGroupId().equals(artifact.getGroupId()) && 
						x.getArtifactId().equals(artifact.getArtifactId()) && 
						x.getExtension().equals(artifact.getExtension()) && 
						x.getClassifier().equals(artifact.getClassifier())){
					// уже копировали, но возможно, щас версия более свежая, чем была. Нужно оставить того, который свежее
					GenericVersionScheme genericVersionScheme = new GenericVersionScheme();
					Version xVersion = genericVersionScheme.parseVersion(x.getVersion()); 					
					Version artifactVersion = genericVersionScheme.parseVersion(artifact.getVersion());
					if (xVersion.compareTo(artifactVersion) >= 0){
						// версия уже скопированного артефакта выше либо такая же, копировать не надо, ничего не делаем
						return;
					} else{
						// старый артефакт удаляем, а новый копируем 
						// (здесь только удаляем, копировать будем по выходу из цикла)
						File artifactToDelete = new File(destinationDirectory + File.separator + x.getFile().getName());
						FileUtils.forceDelete(artifactToDelete);
						copiedArtifacts.remove(x);
						break;
					}
				}
			}
			copiedArtifacts.add(artifact);
			
			File artifactToCopy = new File(destinationDirectory + File.separator + artifact.getFile().getName());
			getLog().debug("Checking: " + artifactToCopy.getAbsolutePath() + "...");
			if (!artifactToCopy.exists()) {
				getLog().debug("Copying: " + artifactToCopy.getAbsolutePath() + "...");
				FileUtils.copyFileToDirectory(artifact.getFile(), destinationDirectory);
			}
		} catch (java.io.IOException | InvalidVersionSpecificationException e) {
			throw new IOException(e.getMessage(), e);
		}
	}

	/**
	 * Extract the configuration settings (not properties files) from the configuration artifact and load them into the /bin directory
	 *
	 * @param artifact Configuration artifact
	 * @throws IOException
	 */
	private void extractConfigSettings(Artifact artifact) throws IOException {
		try (JarFile configSettings = new JarFile(artifact.getFile())) {
			Enumeration<JarEntry> entries = configSettings.entries();
			while (entries.hasMoreElements()) {
				JarEntry jarFileEntry = entries.nextElement();
				// Only interested in files in the /bin directory that are not properties files
				if (!jarFileEntry.isDirectory() && jarFileEntry.getName().startsWith("bin") 
				        && !jarFileEntry.getName().endsWith(".properties")) {
					File fileToCreate = new File(jmeterDirectory, jarFileEntry.getName());
					copyInputStreamToFile(configSettings.getInputStream(jarFileEntry), fileToCreate);
				}
			}
		} catch (java.io.IOException e) {
			throw new IOException(e.getMessage(), e);
		}
	}
}

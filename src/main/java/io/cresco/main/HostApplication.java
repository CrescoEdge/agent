package io.cresco.main;

import org.osgi.framework.*;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;
//import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.util.tracker.ServiceTracker;

import java.io.File;
import java.net.InetAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.UUID;
import java.nio.file.Paths;
import java.nio.file.Path;
import java.nio.file.Files;
public class HostApplication {
    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(HostApplication.class.getName());

    /**
     * Felix-specific framework property carrying a List of BundleActivator instances that are
     * started/stopped with the system bundle. This is the documented Felix config key (formerly
     * referenced via the impl-only constant FelixConstants.SYSTEMBUNDLE_ACTIVATORS_PROP); using
     * the string keeps the launcher off the org.apache.felix.framework.* implementation classes.
     */
    private static final String SYSTEMBUNDLE_ACTIVATORS_PROP = "felix.systembundle.activators";

    /** Max time to wait for a bundle to reach RESOLVED during shutdown before giving up. */
    private static final long BUNDLE_STOP_TIMEOUT_MS = 10_000L;

    private HostActivator m_activator = null;
    private Framework m_felix = null;
    private ServiceTracker m_tracker = null;
    private Config agentConfig = null;
    private FileConfig versionConfig = null;
    private Bundle coreBundle = null;
    //private Bundle controllerBundle = null;
    private Bundle httpBundle = null;
    private Bundle loggerBundle = null;
    private Bundle libraryBundle = null;
    private Bundle consoleBundle = null;
    private Bundle jettyBundle = null;
    private Bundle baseBundle = null;
    public HostApplication()
    {

        Map<String,Object> fileConfigMap =  initAgentConfigMap();

        versionConfig =  initVersionFileConfig();

        agentConfig = new Config(fileConfigMap);

        //LOG.info("Building OSGi Framework");

        // Create a configuration property map.
        Map configMap = new HashMap();
        // Export the host provided service interface package.

        //configMap.put(Constants.FRAMEWORK_SYSTEMPACKAGES_EXTRA, "sun.*,com.sun.*,javax.xml.*");
        //configMap.put("org.osgi.framework.bootdelegation","sun.*,com.sun.*,javax.xml.*");


        configMap.put("felix.log.level","1");
        configMap.put("felix.systempackages.calculate.uses","true");
        configMap.put("felix.systempackages.substitution","true");
        configMap.put("ds.showtrace", "false");
        configMap.put("ds.showerrors", "false");

        configMap.put(Constants.FRAMEWORK_SYSTEMPACKAGES_EXTRA, "sun.*,com.sun.*");
        //configMap.put("org.osgi.framework.bootdelegation","sun.*,com.sun.*,");
        configMap.put("org.osgi.framework.bootdelegation","sun.*,com.sun.*,org.graalvm.*");

        // make sure the cache is cleaned
        configMap.put(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
        //set storage location
        String cresco_data_location = System.getProperty("cresco_data_location");
        if(cresco_data_location != null) {
            Path path = Paths.get(cresco_data_location, "felix-cache");
            configMap.put(Constants.FRAMEWORK_STORAGE, path.toAbsolutePath().normalize().toString());
        } else {
            configMap.put(Constants.FRAMEWORK_STORAGE, "cresco-data/felix-cache");
        }

        //config.put(FRAMEWORK_SYSTEMPACKAGES_EXTRA, this.systemPackages.toString());

        // more properties available at: http://felix.apache.org/documentation/subprojects/apache-felix-service-component-runtime.html


        String httpPort = System.getProperty("port");
        if(httpPort == null) {
            httpPort = System.getenv("CRESCO_port");
        }
        if(httpPort == null) {
            httpPort = "8080";
        }

        //port
        configMap.put("org.osgi.service.http.port", httpPort);

        configMap.put("obr.repository.url","http://felix.apache.org/obr/releases.xml");

        // Create host activator;
        m_activator = new HostActivator();

        List list = new ArrayList();
        list.add(m_activator);
        configMap.put(SYSTEMBUNDLE_ACTIVATORS_PROP, list);

        try {

            Runtime.getRuntime().addShutdownHook(new Thread()
            {
                @Override
                public void run()
                {
                    try {

                        // Stop bundles in dependency order. Each stop is bounded by a timeout so a
                        // bundle that never reaches RESOLVED cannot hang JVM shutdown indefinitely.
                        stopBundleAndWait(consoleBundle);
                        stopBundleAndWait(jettyBundle);
                        stopBundleAndWait(baseBundle);
                        // controller, core, library, logger
                        stopBundleAndWait(getController());
                        stopBundleAndWait(coreBundle);
                        stopBundleAndWait(libraryBundle);
                        stopBundleAndWait(loggerBundle);


                        shutdownApplication();

                        //try and remove data here if needed
                        String tmp_data = agentConfig.getStringParam("tmp_data");

                        if(tmp_data != null) {
                            boolean isTmpData = false;
                            try {
                                isTmpData = Boolean.parseBoolean(tmp_data);
                            } catch (Exception ex) {
                                //eat it
                            }
                            if(isTmpData) {
                                //generate location and set envs
                                //String tmp_dir = System.getProperty("java.io.tmpdir");
                                Path path = Paths.get(System.getProperty("cresco_data_location"));

                                Files.walk(path)
                                        .map(Path::toFile)
                                        .sorted((o1, o2) -> -o1.compareTo(o2))
                                        .forEach(File::delete);
                            }
                        }


                    } catch (Exception ex) {
                        LOG.info("Shutdown Exception");
                        LOG.log(java.util.logging.Level.SEVERE, "exception", ex);
                    }
                }
            });

            boolean enableConsole = agentConfig.getBooleanParam("enable_console",Boolean.FALSE);

            // Create the framework via the standard OSGi launch API (org.osgi.framework.launch)
            // rather than instantiating the Felix implementation class directly. The FrameworkFactory
            // is discovered from META-INF/services on the classpath (provided by org.apache.felix.main).
            FrameworkFactory frameworkFactory =
                    ServiceLoader.load(FrameworkFactory.class, HostApplication.class.getClassLoader())
                            .iterator().next();
            m_felix = frameworkFactory.newFramework(configMap);
            // init() makes a valid BundleContext available; start() raises the framework to ACTIVE.
            m_felix.init();
            m_felix.start();

            BundleContext bc = m_felix.getBundleContext();

            // Install any operator-provided drop-in bundles from the externaljars/ directory
            // (default; overridable via -Dcresco_externaljars_dir). Each jar is installed and
            // started independently: a failure of one jar is logged and skipped rather than
            // aborting the rest (previously a single failed install returned null and the
            // subsequent b.start() NPE'd the whole batch).
            try {
                String externalJarsDir = System.getProperty("cresco_externaljars_dir", "externaljars");
                File directory = new File(externalJarsDir);
                if(directory.isDirectory()) {
                    File[] files = directory.listFiles();
                    if(files != null) {

                        List<Bundle> bundleList = new ArrayList<>();
                        for (File file : files) {
                            if (file.isFile() && file.getName().toLowerCase().endsWith(".jar")) {
                                Bundle installed = installExternalBundleJars(bc, file.getAbsolutePath());
                                if (installed != null) {
                                    bundleList.add(installed);
                                } else {
                                    LOG.info("externaljars: skipping " + file.getAbsolutePath()
                                            + " (install failed)");
                                }
                            }
                        }
                        for(Bundle b : bundleList) {
                            try {
                                b.start();
                            } catch (Exception ex) {
                                LOG.info("externaljars: failed to start bundle "
                                        + b.getSymbolicName() + " : " + ex.getMessage());
                            }
                        }
                    }
                }

            } catch(Exception ex) {
                LOG.log(java.util.logging.Level.SEVERE, "exception", ex);
            }

            //items to make Java > 8 work, work in progress, does not work
            /*
            installInternalBundleJars(bc,"javax.activation-api-1.2.0.jar");
            installInternalBundleJars(bc,"javax.activation-1.2.0.jar");
            installInternalBundleJars(bc,"jaxb-api-2.3.1.jar");
            installInternalBundleJars(bc,"jaxws-api-2.3.1.jar");
            installInternalBundleJars(bc,"javax.xml.soap-api-1.4.0.jar");
            installInternalBundleJars(bc,"jaxb-runtime-2.3.1.jar");
            installInternalBundleJars(bc,"jaxb-impl-2.3.1.jar");
            installInternalBundleJars(bc,"jaxb-core-2.3.0.jar");
            */

            installInternalBundleJars(bc,"org.apache.felix.configadmin-1.9.26.jar").start();
            loggerBundle = installInternalBundleJars(bc,"logger.jar");
            loggerBundle.start();

            installInternalBundleJars(bc,"org.apache.felix.metatype-1.2.4.jar").start();
            installInternalBundleJars(bc,"osgi.cmpn-7.0.0.jar");


            installInternalBundleJars(bc,"org.osgi.util.promise-1.3.0.jar");
            installInternalBundleJars(bc,"org.osgi.util.function-1.2.0.jar");

            // R8 Declarative Services API (org.osgi.service.component 1.5.x) required by Felix SCR 2.2.12.
            // The embedded osgi.cmpn 7.0.0 only exports the DS 1.4 API, so SCR 2.2.12 would otherwise
            // fail to resolve (missing org.osgi.service.component >=1.5.0).
            installInternalBundleJars(bc,"org.osgi.service.component-1.5.1.jar");

            installInternalBundleJars(bc, "org.apache.felix.http.servlet-api-3.0.0.jar").start();

            if(enableConsole) {

                installInternalBundleJars(bc, "commons-io-2.16.1.jar");
                installInternalBundleJars(bc, "commons-fileupload-1.5.jar");
                installInternalBundleJars(bc, "org.osgi.service.useradmin-1.1.1.jar");

                baseBundle = installInternalBundleJars(bc, "org.apache.felix.http.jetty12-1.0.8.jar");
                baseBundle = installInternalBundleJars(bc, "org.apache.felix.inventory-1.1.0.jar");
                baseBundle = installInternalBundleJars(bc, "encoder-1.2.3.jar");
                //baseBundle.start();

                baseBundle = installInternalBundleJars(bc, "org.apache.felix.http.base-5.1.8.jar");
                baseBundle.start();

                jettyBundle = installInternalBundleJars(bc, "org.apache.felix.http.jetty-5.1.16.jar");
                jettyBundle.start();

                consoleBundle = installInternalBundleJars(bc, "org.apache.felix.webconsole-5.0.18.jar");
                consoleBundle.start();
            }


            installInternalBundleJars(bc,"org.apache.felix.gogo.runtime-1.1.6.jar").start();
            installInternalBundleJars(bc,"org.apache.felix.gogo.command-1.1.2.jar").start();
            installInternalBundleJars(bc,"org.apache.felix.scr-2.2.12.jar").start();

            // Felix Health Check API (org.apache.felix.hc.api) — the contract the controller registers
            // HealthCheck services against. API bundle only: it imports just org.osgi.framework and
            // resolves trivially. The HC *core* executor is intentionally NOT provisioned (its R8 deps
            // -- org.osgi.service.condition, org.osgi.service.servlet.context, slf4j [1.7,2) -- are
            // absent from this minimal Felix); the controller runs its own executor. Installed, not
            // started: an API bundle only needs to reach RESOLVED to export its packages.
            installInternalBundleJars(bc,"org.apache.felix.healthcheck.api-2.0.4.jar");

            libraryBundle = installInternalBundleJars(bc,"library.jar");
            libraryBundle.start();

            coreBundle = installInternalBundleJars(bc,"core.jar");
            coreBundle.start();

            String pluginName = "io.cresco.controller";
            String controllerVerion = null;
            try {
                if(versionConfig != null) {
                    controllerVerion = versionConfig.getStringParams(pluginName, "jarfile");
                }
            } catch (Exception ex){
                LOG.log(java.util.logging.Level.SEVERE, "exception", ex);
            }

            String internalController = "controller.jar";

            Bundle controllerBundle = null;

            if (controllerVerion != null) {

                try {
                    controllerBundle = installExternalBundleJars(bc, controllerVerion);
                    if(controllerBundle.getState() == Bundle.INSTALLED) {
                        controllerBundle.start();
                        if(controllerBundle.getState() != Bundle.ACTIVE) {
                            controllerBundle.stop();
                            controllerBundle.uninstall();
                            controllerBundle = null;
                        }
                    }

                } catch (Exception ex) {
                    LOG.log(java.util.logging.Level.SEVERE, "exception", ex);
                    controllerBundle = null;
                }
            }

            if(controllerBundle == null) {
                controllerBundle = installInternalBundleJars(bc, internalController);
                controllerBundle.start();
            }



        }
        catch (Exception ex)
        {
            LOG.severe("Could not create framework: " + ex);
            LOG.log(java.util.logging.Level.SEVERE, "exception", ex);
        }

    }

    private Bundle installInternalBundleJars(BundleContext context, String bundleName) {

        Bundle installedBundle = null;
        try {
            URL bundleURL = getClass().getClassLoader().getResource(bundleName);
            if(bundleURL != null) {

                String bundlePath = bundleURL.getPath();
                installedBundle = context.installBundle(bundlePath,
                        getClass().getClassLoader().getResourceAsStream(bundleName));
                
            } else {
                LOG.info("Bundle = null for " + bundleName);
            }
        } catch(Exception ex) {
            LOG.log(java.util.logging.Level.SEVERE, "exception", ex);
        }

        if(installedBundle == null) {
            LOG.info("installInternalBundleJars() + Failed to load bundle " +bundleName + " exiting!");

            System.exit(0);
        }

        return installedBundle;
    }

    private Bundle installExternalBundleJars(BundleContext context, String bundleName) {

        Bundle installedBundle = null;
        try {
            // Build a well-formed file: URI (handles spaces / platform path differences) instead of
            // string-concatenating "file://" + path. Returns null on failure so the caller can skip
            // this jar; the JVM is not exited.
            String location = new File(bundleName).toURI().toString();
            installedBundle = context.installBundle(location);
        } catch(Exception ex) {
            LOG.info("installExternalBundleJars: failed to install " + bundleName
                    + " : " + ex.getMessage());
        }

        return installedBundle;
    }


    private Map<String,Object> initAgentConfigMap() {
        Map<String, Object> configParams = null;
        try {

            configParams = new HashMap<>();

            String agentConfig = System.getProperty("agentConfig");


            if (agentConfig == null) {
                agentConfig = "conf/agent.ini";
            }


            File configFile = new File(agentConfig);
            FileConfig config = null;
            if (configFile.isFile()) {

                //Agent Config
                config = new FileConfig(configFile.getAbsolutePath());
                configParams = config.getConfigMap();

            }

            //there are cases where we want to change the log directory, this must be sent in the env
            String tmp_data = System.getProperty("tmp_data");
            if(tmp_data == null) {
                tmp_data = System.getenv("CRESCO_tmp_data");
                if(tmp_data == null) {
                    if(config != null) {
                        tmp_data = config.getStringParams("general", "tmp_data");
                    }
                }
            }
            if(tmp_data != null) {
                boolean isTmpData = false;
                try {
                    isTmpData = Boolean.parseBoolean(tmp_data);
                } catch (Exception ex) {
                    //eat it
                }
                if(isTmpData) {
                    //generate location and set envs
                    //String tmp_dir = System.getProperty("java.io.tmpdir");
                    UUID uuid = UUID.randomUUID();
                    Path path = Paths.get("cresco_data", uuid.toString());

                    System.setProperty("cresco_data_location", path.toAbsolutePath().normalize().toString());

                    //this is to prevent derby from logging, which holds onto the log file and prevents it from being removed
                    System.setProperty("derby.stream.error.method", "io.cresco.agent.db.DBLogger.disableDerbyLogFile");

                }
            }

            //create set directory if it does not exist
            String cresco_data_directory = System.getProperty("cresco_data_location");
            if(cresco_data_directory != null) {
                Path path = Paths.get(cresco_data_directory);
                if (!Files.exists(path)) {
                    Files.createDirectories(path);
                }
            }


            /*
            String configMsg = "Property > Env";

            if (config == null) {
                configParams = new HashMap<>();
            } else {
                configMsg = "Property > Env > " + configFile;
            }
            */


            String platform = System.getenv("CRESCO_PLATFORM");
            if (platform == null) {

                if(config != null) {
                    platform = config.getStringParams("general", "platform");
                }

                if (platform == null) {
                    platform = "unknown";
                }
            }

            configParams.put("platform", platform);
            //enableMsg.setParam("platform", platform);

            String environment = System.getenv("CRESCO_ENVIRONMENT");
            if (environment == null) {

                if(config != null) {
                    environment = config.getStringParams("general", "environment");
                }

                if (environment == null) {
                    try {
                        environment = System.getProperty("os.name");
                    } catch (Exception ex) {
                        environment = "unknown";
                    }
                }
            }
            //enableMsg.setParam("environment", environment);
            configParams.put("environment", environment);

            String location = System.getenv("CRESCO_LOCATION");
            if(location == null) {

                if(config != null) {
                    location = config.getStringParams("general", "location");
                }
            }
            if (location == null) {

                try {
                    location = InetAddress.getLocalHost().getHostName();
                    if (location != null) {
                        //logger.info("Location set: " + location);
                    }
                } catch (Exception ex) {
                    //logger.error("getLocalHost() Failed : " + ex.getMessage());
                }

                if (location == null) {
                    try {

                        String osType = System.getProperty("os.name").toLowerCase();
                        if (osType.equals("windows")) {
                            location = System.getenv("COMPUTERNAME");
                        } else if (osType.equals("linux")) {
                            location = System.getenv("HOSTNAME");
                        }

                        if (location != null) {
                            //logger.info("Location set env: " + location);
                        }

                    } catch (Exception exx) {
                        //do nothing
                        //logger.error("Get System Env Failed : " + exx.getMessage());
                    }
                }
            }
            if (location == null) {
                location = "unknown";
            }
            //enableMsg.setParam("location", location);
            configParams.put("location", location);
        } catch (Exception ex) {
            LOG.log(java.util.logging.Level.SEVERE, "exception", ex);
            System.exit(0);
        }
        return configParams;
    }

    private FileConfig initVersionFileConfig() {
        FileConfig config = null;
        try {

            String versionConfig = System.getProperty("versionConfig");


            if (versionConfig == null) {
                versionConfig = "conf/version.ini";
            }


            File configFile = new File(versionConfig);

            if (configFile.isFile()) {

                //Agent Config
                config = new FileConfig(configFile.getAbsolutePath());

            }

        } catch (Exception ex) {
            LOG.log(java.util.logging.Level.SEVERE, "exception", ex);
            System.exit(0);
        }
        return config;
    }


    private boolean startInternalBundleJars(Bundle bundle) {

        try {
            if(bundle != null) {
                int bundleState = bundle.getState();

                if (bundleState == Bundle.INSTALLED) {
                    bundle.start();
                    bundleState = bundle.getState();
                    if (bundleState == Bundle.ACTIVE) {
                        return true;
                    }
                } else {
                    LOG.info("bundle not ready");
                }
            } else {
                LOG.info("startInternalBundleJars Bundle = null ");
            }

        } catch(Exception ex) {
            LOG.log(java.util.logging.Level.SEVERE, "exception", ex);
        }

        return false;
    }

    private String getState(int stateCode) {
        String returnString = null;

        switch (stateCode) {
            case Bundle.UNINSTALLED:  returnString= "Uninstalled";
                break;
            case Bundle.INSTALLED:  returnString= "Installed";
                break;
            case Bundle.RESOLVED:  returnString= "Resolved";
                break;
            case Bundle.STARTING:  returnString= "Starting";
                break;
            case Bundle.STOPPING:  returnString= "Stopping";
                break;
            case Bundle.ACTIVE:  returnString= "Active";
                break;
            default: returnString = "Unknown";
                break;
        }
        return returnString;
    }

    public void printb() {
        for (Bundle bundle : m_activator.getBundles()) {
            if (bundle.getHeaders().get(Constants.FRAGMENT_HOST) == null) {
                LOG.info("state:" + getState(bundle.getState()));
                LOG.info("id:" + bundle.getBundleId());
                LOG.info("location:" + bundle.getLocation());
                LOG.info("version:" + bundle.getVersion());

                LOG.info("---");
            }
        }
    }

    public Bundle[] getInstalledBundles()
    {
        // Use the system bundle activator to gain external
        // access to the set of installed bundles.
        return m_activator.getBundles();
    }


    /**
     * Stop a bundle and wait (bounded by BUNDLE_STOP_TIMEOUT_MS) for it to reach RESOLVED.
     * Null-safe and time-bounded so a bundle that never stops cannot hang JVM shutdown.
     */
    private void stopBundleAndWait(Bundle bundle) {
        if (bundle == null) {
            return;
        }
        try {
            bundle.stop();
            long deadline = System.currentTimeMillis() + BUNDLE_STOP_TIMEOUT_MS;
            while (bundle.getState() != Bundle.RESOLVED
                    && bundle.getState() != Bundle.UNINSTALLED
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
            }
            if (bundle.getState() != Bundle.RESOLVED && bundle.getState() != Bundle.UNINSTALLED) {
                LOG.info("stopBundleAndWait: timed out waiting for "
                        + bundle.getSymbolicName() + " to stop (state="
                        + getState(bundle.getState()) + ")");
            }
        } catch (Exception ex) {
            LOG.log(java.util.logging.Level.SEVERE, "exception", ex);
        }
    }

    public void shutdownApplication()
    {
        // Shut down the felix framework when stopping the
        // host application.
        try {
            m_felix.stop();
            m_felix.waitForStop(0);
        } catch(Exception ex) {
            LOG.log(java.util.logging.Level.SEVERE, "exception", ex);
        }
    }

    public boolean checkService(BundleContext context, String className, String componentName) {

        return checkService(context, className, componentName, 1);

    }
    public boolean checkService(BundleContext context, String className, String componentName, int TRYCOUNT) {
        boolean isStarted = false;

        try {
            ServiceReference<?>[] servRefs = null;
            int count = 0;

            while ((!isStarted) && (count < TRYCOUNT)) {

                String filterString = "(component.name=" + componentName + ")";
                Filter filter = context.createFilter(filterString);

                //servRefs = context.getServiceReferences(PluginService.class.getName(), filterString);
                servRefs = context.getServiceReferences(className, filterString);

                //LOG.info("REFS : " + servRefs.length);
                if (servRefs == null || servRefs.length == 0) {
                    //LOG.info("NULL FOUND NOTHING!");

                } else {
                    //LOG.info("Running Service Count: " + servRefs.length);

                    for (ServiceReference sr : servRefs) {

                        boolean assign = servRefs[0].isAssignableTo(context.getBundle(), className);

                        if(assign) {
                            isStarted = true;
                        }
                    }
                }
                count++;
                Thread.sleep(1000);
            }
            if(servRefs == null) {
                LOG.info("COULD NOT START PLUGIN COULD NOT GET SERVICE");
            }
        } catch (Exception ex) {
            LOG.log(java.util.logging.Level.SEVERE, "exception", ex);
        }
        return isStarted;
    }

    public Bundle getController()  {
        Bundle controllerBundle = null;
        try {

            BundleContext bundleContext = m_felix.getBundleContext();

            for (Bundle bundle : bundleContext.getBundles()) {

                String bundleName = bundle.getSymbolicName();
                if (bundleName != null) {
                    if (bundleName.equals("io.cresco.controller")) {
                        controllerBundle = bundle;
                    }
                }
            }

        } catch (Exception ex) {
            LOG.log(java.util.logging.Level.SEVERE, "exception", ex);
        }
        return controllerBundle;
    }




    /*

    private ConfigurationAdmin getConfigurationAdmin(final BundleContext bundleContext )
    {
        final ServiceReference ref = bundleContext.getServiceReference( ConfigurationAdmin.class.getName() );
        if( ref == null )
        {
            throw new IllegalStateException( "Cannot find a configuration admin service" );
        }
        return (ConfigurationAdmin) bundleContext.getService( ref );
    }

     */

}
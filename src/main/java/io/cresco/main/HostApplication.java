package io.cresco.main;

import org.apache.felix.framework.Felix;
import org.apache.felix.framework.util.FelixConstants;
import org.osgi.framework.*;
//import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.util.tracker.ServiceTracker;

import java.io.File;
import java.net.InetAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.nio.file.Paths;
import java.nio.file.Path;
import java.nio.file.Files;

public class HostApplication
{
    private HostActivator m_activator = null;
    private Felix m_felix = null;
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

        //System.out.println("Building OSGi Framework");

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
        configMap.put("org.osgi.framework.bootdelegation","sun.*,com.sun.*");

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
        configMap.put(FelixConstants.SYSTEMBUNDLE_ACTIVATORS_PROP, list);

        try {

            Runtime.getRuntime().addShutdownHook(new Thread()
            {
                @Override
                public void run()
                {
                    try {

                        if(consoleBundle != null) {
                            consoleBundle.stop();
                            while(consoleBundle.getState() != 4) {
                                Thread.sleep(100);
                            }
                        }

                        if(jettyBundle != null) {
                            jettyBundle.stop();
                            while(jettyBundle.getState() != 4) {
                                Thread.sleep(100);
                            }
                        }

                        if(baseBundle != null) {
                            baseBundle.stop();
                            while(baseBundle.getState() != 4) {
                                Thread.sleep(100);
                            }
                        }


                        Bundle controllerBundle = getController();
                        if(controllerBundle != null) {
                            controllerBundle.stop();

                            while(controllerBundle.getState() != 4) {
                                Thread.sleep(100);
                            }
                        }

                        if(coreBundle != null) {

                            coreBundle.stop();

                             while(coreBundle.getState() != 4) {
                                 Thread.sleep(100);
                             }

                        }
                        //controller, core, library, logger

                        if(libraryBundle != null) {
                            libraryBundle.stop();
                            while(libraryBundle.getState() != 4) {
                                Thread.sleep(100);
                            }
                        }

                        if(loggerBundle != null) {
                            loggerBundle.stop();
                            while(loggerBundle.getState() != 4) {
                                Thread.sleep(100);
                            }
                        }


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
                        System.out.println("Shutdown Exception");
                        ex.printStackTrace();
                    }
                }
            });

            boolean enableConsole = agentConfig.getBooleanParam("enable_console",Boolean.FALSE);

            // Now create an instance of the framework with
            // our configuration properties.
            m_felix = new Felix(configMap);
            // Now start Felix instance.
            m_felix.start();

            BundleContext bc = m_felix.getBundleContext();

            //install any user-provided bundle dependencies
            try {
                File directory = new File("externaljars");
                if(directory.exists()) {
                    File[] files = directory.listFiles();
                    if(files != null) {

                        List<Bundle> bundleList = new ArrayList<>();
                        for (File file : files) {
                            if (file.isFile()) {
                                bundleList.add(installExternalBundleJars(bc,file.getAbsolutePath()));
                            }
                        }
                        for(Bundle b : bundleList) {
                            b.start();
                        }
                    }
                }

            } catch(Exception ex) {
                ex.printStackTrace();
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

            installInternalBundleJars(bc,"org.apache.felix.configadmin-1.9.20.jar").start();
            loggerBundle = installInternalBundleJars(bc,"logger-1.1-SNAPSHOT.jar");
            loggerBundle.start();

            installInternalBundleJars(bc,"org.apache.felix.metatype-1.2.4.jar").start();
            installInternalBundleJars(bc,"osgi.cmpn-7.0.0.jar");


            installInternalBundleJars(bc,"org.osgi.util.promise-1.1.0.jar");
            installInternalBundleJars(bc,"org.osgi.util.function-1.1.0.jar");

            installInternalBundleJars(bc, "org.apache.felix.http.servlet-api-1.1.2.jar").start();
            if(enableConsole) {

                installInternalBundleJars(bc, "commons-io-1.4.jar");
                installInternalBundleJars(bc, "commons-fileupload-1.4.jar");
                baseBundle = installInternalBundleJars(bc, "org.apache.felix.http.base-4.1.2.jar");
                baseBundle.start();

                jettyBundle = installInternalBundleJars(bc, "org.apache.felix.http.jetty-4.1.4.jar");
                jettyBundle.start();

                consoleBundle = installInternalBundleJars(bc, "org.apache.felix.webconsole-4.5.4.jar");
                consoleBundle.start();
            }


            installInternalBundleJars(bc,"org.apache.felix.gogo.runtime-1.1.4.jar").start();
            installInternalBundleJars(bc,"org.apache.felix.gogo.command-1.1.2.jar").start();
            installInternalBundleJars(bc,"org.apache.felix.scr-2.1.20.jar").start();
            libraryBundle = installInternalBundleJars(bc,"library-1.1-SNAPSHOT.jar");
            libraryBundle.start();

            coreBundle = installInternalBundleJars(bc,"core-1.1-SNAPSHOT.jar");
            coreBundle.start();

            String pluginName = "io.cresco.controller";
            String controllerVerion = null;
            try {
                if(versionConfig != null) {
                    controllerVerion = versionConfig.getStringParams(pluginName, "jarfile");
                }
            } catch (Exception ex){
                ex.printStackTrace();
            }


            String internalController = "controller-1.1-SNAPSHOT.jar";

            Bundle controllerBundle = null;

            if (controllerVerion != null) {

                try {
                    controllerBundle = installExternalBundleJars(bc, controllerVerion);
                    if(controllerBundle.getState() == 2) {
                        controllerBundle.start();
                        if(controllerBundle.getState() != 32) {
                            controllerBundle.stop();
                            controllerBundle.uninstall();
                            controllerBundle = null;
                        }
                    }

                } catch (Exception ex) {
                    ex.printStackTrace();
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
            System.err.println("Could not create framework: " + ex);
            ex.printStackTrace();
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
                System.out.println("Bundle = null for " + bundleName);
            }
        } catch(Exception ex) {
            ex.printStackTrace();
        }

        if(installedBundle == null) {
            System.out.println("installInternalBundleJars() + Failed to load bundle " +bundleName + " exiting!");

            System.exit(0);
        }

        return installedBundle;
    }

    private Bundle installExternalBundleJars(BundleContext context, String bundleName) {

        
        Bundle installedBundle = null;
        try {
            //URL bundleURL = new URL("file://" + bundleName);
            //if(bundleURL != null) {

                installedBundle = context.installBundle("file://" + bundleName);


            //} else {
            //    System.out.println("Bundle = null for " + bundleName);
            //}
        } catch(Exception ex) {
            ex.printStackTrace();
        }

        if(installedBundle == null) {
            System.out.println("installInternalBundleJars() + Failed to load bundle " +bundleName + " exiting!");

            //System.exit(0);
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
            ex.printStackTrace();
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
            ex.printStackTrace();
            System.exit(0);
        }
        return config;
    }


    private boolean startInternalBundleJars(Bundle bundle) {

        try {
            if(bundle != null) {
                int bundleState = bundle.getState();

                if (bundleState == 2) {
                    bundle.start();
                    bundleState = bundle.getState();
                    if (bundleState == 32) {
                        return true;
                    }
                } else {
                    System.out.println("bundle not ready");
                }
            } else {
                System.out.println("startInternalBundleJars Bundle = null ");
            }

        } catch(Exception ex) {
            ex.printStackTrace();
        }

        return false;
    }

    private String getState(int stateCode) {
        String returnString = null;

        switch (stateCode) {
            case 1:  returnString= "Uninstalled";
                break;
            case 2:  returnString= "Installed";
                break;
            case 4:  returnString= "Resolved";
                break;
            case 8:  returnString= "Starting";
                break;
            case 16:  returnString= "Stopping";
                break;
            case 32:  returnString= "Active";
                break;
            default: returnString = "Unknown";
                break;
        }
        return returnString;
    }

    public void printb() {
        for (Bundle bundle : m_activator.getBundles()) {
            if (bundle.getHeaders().get(Constants.FRAGMENT_HOST) == null) {
                System.out.println("state:" + getState(bundle.getState()));
                System.out.println("id:" + bundle.getBundleId());
                System.out.println("location:" + bundle.getLocation());
                System.out.println("version:" + bundle.getVersion());

                System.out.println("---");
            }
        }
    }

    public Bundle[] getInstalledBundles()
    {
        // Use the system bundle activator to gain external
        // access to the set of installed bundles.
        return m_activator.getBundles();
    }


    public void shutdownApplication()
    {
        // Shut down the felix framework when stopping the
        // host application.
        try {
            m_felix.stop();
            m_felix.waitForStop(0);
        } catch(Exception ex) {
            ex.printStackTrace();
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

                //System.out.println("REFS : " + servRefs.length);
                if (servRefs == null || servRefs.length == 0) {
                    //System.out.println("NULL FOUND NOTHING!");

                } else {
                    //System.out.println("Running Service Count: " + servRefs.length);

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
                System.out.println("COULD NOT START PLUGIN COULD NOT GET SERVICE");
            }
        } catch (Exception ex) {
            ex.printStackTrace();
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
            ex.printStackTrace();
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
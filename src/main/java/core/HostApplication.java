package core;

import org.apache.felix.framework.Felix;
import org.apache.felix.framework.util.FelixConstants;
import org.osgi.framework.*;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.util.tracker.ServiceTracker;

import java.io.File;
import java.net.InetAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class HostApplication
{
    private HostActivator m_activator = null;
    private Felix m_felix = null;
    private ServiceTracker m_tracker = null;
    private Config agentConfig = null;
    private Bundle coreBundle = null;
    private Bundle controllerBundle = null;
    private Bundle httpBundle = null;

    public HostApplication()
    {

        Map<String,Object> fileConfigMap =  initAgentConfigMap();

        agentConfig = new Config(fileConfigMap);

        //System.out.println("Building OSGi Framework");

        // Create a configuration property map.
        Map configMap = new HashMap();
        // Export the host provided service interface package.
        //configMap.put(Constants.FRAMEWORK_SYSTEMPACKAGES_EXTRA, "sun.*,com.sun.*,javax.xml.*");
        //configMap.put("org.osgi.framework.bootdelegation","sun.*,com.sun.*,javax.xml.*");
        configMap.put("felix.systempackages.calculate.uses","true");
        configMap.put("felix.systempackages.substitution","true");

        configMap.put(Constants.FRAMEWORK_SYSTEMPACKAGES_EXTRA, "sun.*,com.sun.*");
        configMap.put("org.osgi.framework.bootdelegation","sun.*,com.sun.*");

        // make sure the cache is cleaned
        configMap.put(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);

        //config.put(FRAMEWORK_SYSTEMPACKAGES_EXTRA, this.systemPackages.toString());

        // more properties available at: http://felix.apache.org/documentation/subprojects/apache-felix-service-component-runtime.html
        configMap.put("ds.showtrace", "true");
        configMap.put("ds.showerrors", "true");

        configMap.put("felix.log.level","1");

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

                         if (httpBundle != null) {
                             httpBundle.stop();
                         }

                        if(coreBundle != null) {

                            coreBundle.stop();

                             while(coreBundle.getState() != 4) {
                                 Thread.sleep(100);
                             }

                        }


                        if(controllerBundle != null) {
                             controllerBundle.stop();
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

            installInternalBundleJars(bc,"org.apache.felix.configadmin-1.9.14.jar").start();
            installInternalBundleJars(bc,"logger-1.0-SNAPSHOT.jar").start();

            installInternalBundleJars(bc,"org.apache.felix.metatype-1.2.2.jar").start();

            installInternalBundleJars(bc,"osgi.cmpn-7.0.0.jar");


            installInternalBundleJars(bc,"org.osgi.util.promise-1.1.0.jar");
            installInternalBundleJars(bc,"org.osgi.util.function-1.1.0.jar");


            if(enableConsole) {
                installInternalBundleJars(bc, "org.apache.felix.http.servlet-api-1.1.2.jar").start();
                installInternalBundleJars(bc, "org.apache.felix.http.base-4.0.6.jar").start();
                installInternalBundleJars(bc, "org.apache.felix.http.jetty-4.0.8.jar").start();
                installInternalBundleJars(bc, "org.apache.felix.webconsole-4.3.8-all.jar").start();
            }

            installInternalBundleJars(bc,"org.apache.felix.gogo.runtime-1.1.2.jar").start();
            installInternalBundleJars(bc,"org.apache.felix.gogo.command-1.1.0.jar").start();
            installInternalBundleJars(bc,"org.apache.felix.scr-2.1.16.jar").start();
            installInternalBundleJars(bc,"library-1.0-SNAPSHOT.jar").start();



            coreBundle = installInternalBundleJars(bc,"core-1.0-SNAPSHOT.jar");
            coreBundle.start();

            controllerBundle = installInternalBundleJars(bc,"controller-1.0-SNAPSHOT.jar");
            controllerBundle.start();




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

            System.exit(0);
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



    private ConfigurationAdmin getConfigurationAdmin(final BundleContext bundleContext )
    {
        final ServiceReference ref = bundleContext.getServiceReference( ConfigurationAdmin.class.getName() );
        if( ref == null )
        {
            throw new IllegalStateException( "Cannot find a configuration admin service" );
        }
        return (ConfigurationAdmin) bundleContext.getService( ref );
    }

}
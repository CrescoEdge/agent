package core;

import org.apache.felix.framework.Felix;
import org.apache.felix.framework.util.FelixConstants;
import org.osgi.framework.*;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.util.tracker.ServiceTracker;

import java.io.File;
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

    public HostApplication()
    {

        System.out.println("Building OSGi Framework");

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
            httpPort = "8181";
        }



        //port
        configMap.put("org.osgi.service.http.port", httpPort);

        configMap.put("obr.repository.url","http://felix.apache.org/obr/releases.xml");

        // Create host activator;
        m_activator = new HostActivator();

        List list = new ArrayList();
        list.add(m_activator);
        configMap.put(FelixConstants.SYSTEMBUNDLE_ACTIVATORS_PROP, list);

        try
        {

            boolean enableHttp = false;
            if(System.getenv("CRESCO_enable_http") != null) {
                enableHttp = Boolean.parseBoolean(System.getenv("CRESCO_enable_http"));
            } else {
                enableHttp = Boolean.parseBoolean(System.getProperty("enable_http","false"));
            }

           boolean enableConsole = false;
            if(System.getenv("CRESCO_enable_console") != null) {
                enableConsole = Boolean.parseBoolean(System.getenv("CRESCO_enable_console"));
            } else {
                enableConsole = Boolean.parseBoolean(System.getProperty("enable_console", "false"));
            }

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

            installInternalBundleJars(bc,"org.apache.felix.configadmin-1.9.10.jar").start();
            installInternalBundleJars(bc,"core-1.0-SNAPSHOT.jar").start();


            installInternalBundleJars(bc,"org.apache.felix.metatype-1.2.2.jar").start();

            installInternalBundleJars(bc,"osgi.cmpn-7.0.0.jar");


            if(enableConsole || enableHttp) {
                installInternalBundleJars(bc, "org.apache.felix.http.servlet-api-1.1.2.jar").start();

                installInternalBundleJars(bc, "org.apache.felix.http.base-4.0.4.jar").start();
                installInternalBundleJars(bc, "org.apache.felix.http.jetty-4.0.4.jar").start();
                //for RS-JAX
                installInternalBundleJars(bc,"org.apache.aries.javax.jax.rs-api-1.0.1.jar");
                installInternalBundleJars(bc,"org.apache.servicemix.specs.annotation-api-1.3-1.3_1.jar");
                installInternalBundleJars(bc,"org.osgi.service.jaxrs-1.0.0.jar");
                installInternalBundleJars(bc,"org.osgi.service.http.whiteboard-1.1.0.jar");
                installInternalBundleJars(bc,"org.osgi.util.promise-1.1.0.jar");
                installInternalBundleJars(bc,"org.osgi.util.function-1.1.0.jar");
                installInternalBundleJars(bc,"org.apache.aries.jax.rs.whiteboard-1.0.1.jar").start();

            }
            if(enableConsole) {
                installInternalBundleJars(bc, "org.apache.felix.webconsole-4.3.8-all.jar").start();
            }

            installInternalBundleJars(bc,"org.apache.felix.gogo.runtime-1.1.0.jar").start();
            installInternalBundleJars(bc,"org.apache.felix.gogo.command-1.0.2.jar").start();
            installInternalBundleJars(bc,"org.apache.felix.scr-2.1.12.jar").start();

            installInternalBundleJars(bc,"library-1.0-SNAPSHOT.jar").start();

            installInternalBundleJars(bc,"controller-1.0-SNAPSHOT.jar").start();

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
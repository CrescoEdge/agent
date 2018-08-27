package core;

import org.apache.felix.framework.Felix;
import org.apache.felix.framework.util.FelixConstants;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.util.tracker.ServiceTracker;

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
        //System.setProperty("felix.fileinstall.dir", "/Users/cody/IdeaProjects/firstosgi/target/");
        //System.setProperty("felix.fileinstall.noInitialDelay", "true");
        //System.setProperty("felix.fileinstall.poll", "1000");

        System.out.println("Building OSGi Framework");

        // Create a configuration property map.
        Map configMap = new HashMap();
        // Export the host provided service interface package.
        configMap.put(Constants.FRAMEWORK_SYSTEMPACKAGES_EXTRA,
                "sun.*,com.sun.*,javax.xml.*");
        configMap.put("org.osgi.framework.bootdelegation","sun.*,com.sun.*,javax.xml.*");


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


            installInternalBundleJars(bc,"org.apache.felix.configadmin-1.9.2.jar").start();
            installInternalBundleJars(bc,"core-1.0-SNAPSHOT.jar").start();


            installInternalBundleJars(bc,"org.apache.felix.metatype-1.2.0.jar").start();

            installInternalBundleJars(bc,"osgi.cmpn-7.0.0.jar");

            if(enableConsole || enableHttp) {
                installInternalBundleJars(bc, "org.apache.felix.http.servlet-api-1.1.2.jar").start();
                installInternalBundleJars(bc, "org.apache.felix.http.api-3.0.0.jar").start();
                installInternalBundleJars(bc, "org.apache.felix.http.jetty-4.0.0.jar").start();
                installInternalBundleJars(bc, "jersey-all-2.22.2.jar").start();
            }
            if(enableConsole) {
                installInternalBundleJars(bc, "org.apache.felix.webconsole-4.3.4-all.jar").start();
            }

            installInternalBundleJars(bc,"org.apache.felix.gogo.runtime-1.1.0.jar").start();
            installInternalBundleJars(bc,"org.apache.felix.gogo.command-1.0.2.jar").start();

            //installInternalBundleJars(bc,"org.apache.felix.bundlerepository-2.0.10.jar").start();

            installInternalBundleJars(bc,"org.apache.felix.scr-2.1.0.jar").start();


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

        //System.out.println("installing bundle: " + bundleName);


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
}
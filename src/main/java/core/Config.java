package core;


import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cresco configuration handler
 * @author V.K. Cody Bumgardner
 * @author Caylin Hickey
 * @since 0.1.0
 */
public class Config {
    /** Environmental Variable Prefix */
    private static final String ENV_PREFIX = "CRESCO_";

    private AtomicBoolean lockConfig = new AtomicBoolean();
    /** Plugin configuration object */
    protected Map<String,Object> configMap;
    /**
     * Constructor
     * @param configMap     Plugin configuration object
     */
    public Config(Map<String,Object> configMap) {

        this.configMap = Collections.synchronizedMap(new HashMap<>());
        this.configMap.putAll(configMap);
    }
    /**
     * Grab configuration entry as Boolean
     * @param param             Entry name to retrieve
     * @return                  Value of entry, null if missing
     */
    public Boolean getBooleanParam(String param) {


        String env = System.getProperty(param);

        if(env == null) {
            env = System.getenv(ENV_PREFIX + param);
        }

        if (env != null) {

            synchronized (lockConfig) {
                configMap.put(param,env);
            }

            if (env.toLowerCase().trim().equals("true") || env.trim().equals("1")) {
                return true;
            }
            if (env.toLowerCase().trim().equals("false") || env.trim().equals("0")) {
                return false;
            }
        }

        try {

            if(configMap.containsKey(param)) {
                return Boolean.parseBoolean((String)configMap.get(param));
            } else {
                return null;
            }
        } catch (NoSuchElementException e) {
            return null;
        }

    }
    /**
     * Grab configuration entry as Boolean
     * @param param             Entry name to retrieve
     * @param ifNull            Default value to return on error
     * @return                  Value of entry, ifNull value on error
     */
    public Boolean getBooleanParam(String param, Boolean ifNull) {
        Boolean ret = getBooleanParam(param);
        if (ret != null)
            return ret;
        return ifNull;
    }
    /**
     * Grab configuration entry as Double
     * @param param             Entry name to retrieve
     * @return                  Value of entry, null if missing
     */
    public Double getDoubleParam(String param) {
        try {
            String env = System.getProperty(param);
            if(env == null) {
                env = System.getenv(ENV_PREFIX + param);
            }

            if(env != null) {
                synchronized (lockConfig) {
                    configMap.put(param,env);
                }
            }

            return Double.parseDouble(env);

        } catch (NumberFormatException nfe) {
            try {
                if(configMap.containsKey(param)) {
                    return Double.parseDouble((String) configMap.get(param));
                } else {
                    return null;
                }

            } catch (NoSuchElementException nsee) {
                return null;
            }
        }
    }
    /**
     * Grab configuration entry as Double
     * @param param             Entry name to retrieve
     * @param ifNull            Default value to return on error
     * @return                  Value of entry, ifNull value on error
     */
    public Double getDoubleParam(String param, Double ifNull) {
        Double ret = getDoubleParam(param);
        if (ret != null)
            return ret;
        return ifNull;
    }
    /**
     * Grab configuration entry as Integer
     * @param param             Entry name to retrieve
     * @return                  Value of entry, null if missing
     */
    public Integer getIntegerParam(String param) {
        try {
            String env = System.getProperty(param);
            if(env == null) {
                env = System.getenv(ENV_PREFIX + param);
            }

            if(env != null) {
                synchronized (lockConfig) {
                    configMap.put(param,env);
                }
            }

            return Integer.parseInt(env);
        } catch (NumberFormatException nfe) {
            try {
                if(configMap.containsKey(param)) {
                    return Integer.parseInt((String) configMap.get(param));
                } else {
                    return null;
                }

            } catch (NoSuchElementException nsee) {
                return null;
            }
        }
    }
    /**
     * Grab configuration entry as Integer
     * @param param             Entry name to retrieve
     * @param ifNull            Default value to return on error
     * @return                  Value of entry, ifNull value on error
     */
    public Integer getIntegerParam(String param, Integer ifNull) {
        Integer ret = getIntegerParam(param);
        if (ret != null)
            return ret;
        return ifNull;
    }
    /**
     * Grab configuration entry as Long
     * @param param             Entry name to retrieve
     * @return                  Value of entry, null if missing
     */
    public Long getLongParam(String param) {
        try {
            String env = System.getProperty(param);
            if(env == null) {
                env = System.getenv(ENV_PREFIX + param);
            }

            if(env != null) {
                synchronized (lockConfig) {
                    configMap.put(param,env);
                }
            }

            return Long.parseLong(env);
        } catch (NumberFormatException nfe) {
            try {
                if(configMap.containsKey(param)) {
                    return Long.parseLong((String)(configMap.get(param)));
                } else {
                    return null;
                }
            } catch (NoSuchElementException nsee) {
                return null;
            }
        }
    }
    /**
     * Grab configuration entry as Long
     * @param param             Entry name to retrieve
     * @param ifNull            Default value to return on error
     * @return                  Value of entry, ifNull value on error
     */
    public Long getLongParam(String param, Long ifNull) {
        Long ret = getLongParam(param);
        if (ret != null)
            return ret;
        return ifNull;
    }
    /**
     * Grab configuration entry as String
     * @param param             Entry name to retrieve
     * @return                  Value of entry, null if missing
     */
    public String getStringParam(String param) {

        String env = System.getProperty(param);
        if(env == null) {
            env = System.getenv(ENV_PREFIX + param);
        }

        if(env != null) {
            synchronized (lockConfig) {
                configMap.put(param,env);
            }
            return env;
        }

        try {
            if(configMap.containsKey(param)) {
                return (String)configMap.get(param);
            } else {
                return null;
            }
        } catch (NoSuchElementException e) {
            return null;
        }
    }
    /**
     * Grab configuration entry as String
     * @param param             Entry name to retrieve
     * @param ifNull            Default value to return on error
     * @return                  Value of entry, ifNull value on error
     */
    public String getStringParam(String param, String ifNull) {
        String ret = getStringParam(param);
        if (ret != null)
            return ret;
        return ifNull;
    }
    /**
     * Returns the underlying configuration object
     * @return                  The underlying configuration object
     */

    /**
     * Returns a JSON representation of the configuration object
     * @return                  JSONified configuration object
     */

    public Map<String,Object> getConfigMap() {

        Map<String,Object> returnConfigMap = new HashMap<>();
        synchronized (lockConfig) {
            returnConfigMap.putAll(this.configMap);
        }
        return returnConfigMap;
    }

    public String getConfigAsJSON() {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        Iterator it = this.configMap.entrySet().iterator();

        while (it.hasNext()) {
            String key = (String)it.next();
            String value = String.valueOf(this.configMap.get(key));
            sb.append("\"");
            sb.append(key);
            sb.append("\":\"");
            sb.append(value);
            sb.append("\",");
        }
        if (sb.lastIndexOf(",") > -1)
            sb.deleteCharAt(sb.lastIndexOf(","));
        sb.append("}");
        return sb.toString();
    }
}
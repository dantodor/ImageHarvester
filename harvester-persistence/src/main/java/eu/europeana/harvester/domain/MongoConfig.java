package eu.europeana.harvester.domain;

import com.mongodb.DB;
import com.mongodb.Mongo;
import com.mongodb.ServerAddress;
import com.typesafe.config.Config;
import org.apache.commons.lang3.StringUtils;

import java.net.UnknownHostException;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

public class MongoConfig {

    private final List<ServerAddress> mongoServerAddressList;


    /**
     * The DB name.
     */
    private final String dbName;

    /**
     * The username of the  DB if it is secured.
     */
    private final String username;

    /**
     * The password of the DB if it is secured.
     */
    private final String password;


    public MongoConfig(List<ServerAddress> mongoServerAddressList, String dbName, String username, String password) {
        this.mongoServerAddressList = mongoServerAddressList;
        this.dbName = dbName;
        this.username = username;
        this.password = password;
    }

    public static MongoConfig valueOf(final Config config) throws UnknownHostException {
        final List<ServerAddress> serverAddresses = new LinkedList<>();

        for (final Config hostConfig: config.getConfigList("hosts")) {
            serverAddresses.add(new ServerAddress(hostConfig.getString("host"), hostConfig.getInt("port")));
        }

        return new MongoConfig(serverAddresses,
                               config.getString("dbName"),
                               config.getString("username"),
                               config.getString("password")
                             );
    }


    public Mongo connectToMongo() {
       final Mongo mongo = new Mongo(mongoServerAddressList);

        if (StringUtils.isNotEmpty(username)) {
           if (!mongo.getDB("admin").authenticate(username, password.toCharArray())) {
               return null;
           }
        }

        return mongo;
    }

    public DB connectToDB () {
        final Mongo mongo = connectToMongo();
       return null != mongo ? mongo.getDB(dbName) : null;
    }

    public String getDbName () {
        return dbName;
    }

    public String getUsername () {
        return username;
    }

    public String getPassword () {
        return password;
    }

    public List<ServerAddress> getMongoServerAddressList () {
        return mongoServerAddressList;
    }

}
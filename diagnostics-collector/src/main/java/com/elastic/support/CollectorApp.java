package com.elastic.support;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.joran.spi.JoranException;
import com.beust.jcommander.JCommander;
import com.elastic.support.diagnostics.CollectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CollectorApp {

	private static final Logger logger = LoggerFactory.getLogger(CollectorApp.class);

	public static void main(String[] args) throws Exception{

        InputParams inputs = new InputParams();
        JCommander jc = new JCommander(inputs);
        jc.setCaseSensitiveOptions(true);

        try{
            // Parse the incoming arguments from the command line
            // Assuming we didn't get an exception, do a final check to validate
            // whether if a username is entered so is a passoword, or vice versa.
            jc.parse(args);
        }
        catch(RuntimeException e){
            System.out.println("Error:" + e.getMessage());
            jc.usage();
            System.exit(-1);
        }

        if(inputs.isHelp()){
            jc.usage();
            System.exit(1);
        }

        // Check for an override to the logging config.
        // If it gets an error use the defaults and continue
        if(inputs.getLogConfig() != null){
            LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();

            try {
                JoranConfigurator configurator = new JoranConfigurator();
                configurator.setContext(context);
                context.reset();
                configurator.doConfigure(args[0]);
            } catch (JoranException je) {
                System.out.println("Error was encountered while overridding log configuration.  Defaults will be used.");
            }
        }

        CollectionService collectionService = new CollectionService();

        try {
            collectionService.run(inputs);
        }
        catch (RuntimeException re){
            System.out.println("An error occurred while retrieving statistics. " + re.getMessage());
        }
    }

    private static boolean validateAuth(String userName, String password) {
        return ! ( (userName != null && password == null) || (password != null && userName == null) );

    }

}

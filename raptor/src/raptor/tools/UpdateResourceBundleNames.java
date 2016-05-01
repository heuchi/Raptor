package raptor.tools;

import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.apache.commons.lang.StringUtils;

public class UpdateResourceBundleNames {

	public static void main(String[] args) throws Exception {

		File file = new File("/Users/carsonday/git/Raptor/raptor/resources/scripts/action");
		File[] propertyFiles = file.listFiles(new FileFilter() {
			@Override
			public boolean accept(File pathname) {
				return pathname.isFile() && pathname.getAbsolutePath().endsWith(".properties");
			}
		});

		Properties resourceBundle = new Properties();
		FileReader reader = new FileReader(
				new File("/Users/carsonday/git/Raptor/raptor/src/raptor/international/Messages_en.properties"));
		resourceBundle.load(reader);
		reader.close();

		Map<String, String[]> newProps = new HashMap<String, String[]>();

		for (File propFile : propertyFiles) {
			String fileName = propFile.getName();
			System.err.println(fileName);
			String firstPartOfFileName = fileName.split("\\.")[0];
			String firstPartOfFileNameNoSpace = StringUtils.remove(firstPartOfFileName, " ");

			Properties properties = new Properties();
			FileInputStream fileIn = new FileInputStream(propFile);
			properties.load(fileIn);
			fileIn.close();

			String nameValue = properties.getProperty("name");
			String descriptionValue = properties.getProperty("description");

			if (StringUtils.isBlank(nameValue) || StringUtils.isBlank(descriptionValue)) {
				System.err.println("Could not find name or description in properties: " + propFile.getAbsolutePath());
			} else {
				String oldKey = descriptionValue;
				properties.setProperty("name", "$" + firstPartOfFileNameNoSpace + ".name");
				properties.setProperty("description", "$" + firstPartOfFileNameNoSpace + ".description");

				newProps.put("$" + firstPartOfFileNameNoSpace,
						new String[] { firstPartOfFileName, firstPartOfFileName });

				FileOutputStream fileOut = new FileOutputStream(propFile);
				properties.store(fileOut, "Updated with UpdateResourceBundleNames on " + new Date());
				fileOut.close();
			}
			System.err.println("Processed " + propFile.getAbsolutePath());
		}

		// Now update all resource bundles
		File resourceBundleDir = new File("/Users/carsonday/git/Raptor/raptor/src/raptor/international");
		File[] resourceBundleFiles = resourceBundleDir.listFiles(new FileFilter() {
			@Override
			public boolean accept(File pathname) {
				return pathname.isFile() && pathname.getAbsolutePath().endsWith(".properties");
			}
		});

		for (File currentBundle : resourceBundleFiles) {
			Properties properties = new Properties();
			FileInputStream fileIn = new FileInputStream(currentBundle);
			properties.load(fileIn);
			fileIn.close();

			for (String key : newProps.keySet()) {
				String description = properties.getProperty(key);
				if (description != null) {
					String[] array = newProps.get(key);
					array[1] = description;
				}
			}

			FileWriter fileWriter = new FileWriter(currentBundle, true);
			fileWriter.write("\n");
			for (String key : newProps.keySet()) {
				String[] value = newProps.get(key);
				fileWriter.write(key + ".name=" + value[0] + "\n");
				fileWriter.write(key + ".description=" + value[1] + "\n");
			}
			fileWriter.flush();
			fileWriter.close();
		}

	}

}

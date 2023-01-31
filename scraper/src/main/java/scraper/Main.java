package scraper;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Pattern;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Scanner;
import java.util.regex.Matcher;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;


public class Main {
	public static final String LI_AT = "<li_at cookie for LinkedIn session>";
	public static final String JSESSIONID = "JSESSIONID cookie for LinkedIn session";
	public static final String NOTION_DATABASE_URL = "<notion database URL";
	public static final String NOTION_API_TOKEN = "<Notion api token. Needs to be "
			+ "enabled for the specific database>";


	
	/**
	* Connects to LinkedIn account without cookies. Username and password must be provided
	* Returns the content of the URL passed as input.
	* Should not be used as it might trigger account security actions
	* 
	* @param   url  an absolute URL to be fetched and returned
	* @return  returns the webpage to be fetched as Document
	*/
/*		public static Document myLoginWithoutCookies() throws IOException{
	
			String url = "https://www.linkedin.com/uas/login?goback=&trk=hb_signin";
			Connection.Response response = Jsoup
					.connect(url)
					.method(Connection.Method.GET)
					.execute();
	
			Document responseDocument = response.parse();
			Element loginCsrfParam = responseDocument
					.select("input[name=loginCsrfParam]")
					.first();
	
			response = Jsoup.connect("https://www.linkedin.com/uas/login-submit")
					.cookies(response.cookies())
					.data("loginCsrfParam", loginCsrfParam.attr("value"))
					.data("session_key", "<user_email>")
					.data("session_password", "<user_password>")
					.method(Connection.Method.POST)
					.followRedirects(true)
					.execute();
	
			Document document = response.parse();
	
			return document;
		}
*/

	/**
	* Connects to LinkedIn account using the JSESSIONID and LI_AT provided
	* Returns the content of the URL passed as input
	* 
	* @throws IOException  If an input or output 
	*                      exception occurred
	* @param   url  an absolute URL to be fetched and returned
	* @return  returns the webpage to be fetched as Document	
	*/
	public static Document myLoginWithCookies(String urlToFetch) throws IOException{
		String url = "https://www.linkedin.com/uas/login?goback=&trk=hb_signin";
		Connection.Response response = Jsoup
				.connect(url)
				.method(Connection.Method.GET)
				.execute();

		Document responseDocument = response.parse();
		Element loginCsrfParam = responseDocument
				.select("input[name=loginCsrfParam]")
				.first();

		response.cookie("li_at", LI_AT);
		response.cookie("JSESSIONID", JSESSIONID);

		Document doc = Jsoup.connect(urlToFetch)
				.data("loginCsrfParam", loginCsrfParam.attr("value"))
				.cookies(response.cookies())
				.userAgent("Chrome")
				.method(Connection.Method.POST)
				.followRedirects(true)
				.get();

		return doc;
	}

	
	/**
	* Parses the doc provided
	* First it creates a list of saved posts in the order they're seen.
	* They're inserted in a LinkedHashMap to avoid duplicates but the maintain 
	* order of appearance. Then it does another iteration of scraping to populate
	* the rest of the fields we want: link, text and author of post 
	* It then calls sendToNotion with the LinkedHashMap as parameter
	*
	* @throws IOException  If an input or output 
	*                      exception occurred
	*
	* @param   doc The Document file of the page to scrape
	*/
	public static void parseSavedPosts(Document doc) throws IOException {
		
		//Order of the complete saved posts in JSON is different than the order of list of the urns returned with the query 
		//so we use this method to always maintain the order and thus the latest post scraped
		LinkedHashMap<String, String[]> toAdd = parseAndKeepEveryPostUrn(doc.text());
		
		String[] parts = doc.text().split("Impossible de retirer le post"); //English: Post could not be unsaved

		Pattern pattern = Pattern.compile("ImageAttribute\"}],\"accessibilityText\":\"(.*?)\"");
		Pattern pattern2 = Pattern.compile("fs_updateV2:\\(urn:li:activity:(.*?),");
		Pattern pattern3 = Pattern.compile("\"summary\":\\{\"textDirection\":\"USER_LOCALE\",\"text\":\"(.*?)\",\"attributesV2\"");

		for(String post : parts) {
			Matcher matcher = pattern.matcher(post);
			String link, text, author;
			if (matcher.find())
			{
				author = matcher.group(0).substring(39,matcher.group(0).length()-1);

				Matcher matcher2 = pattern2.matcher(post);
				if (matcher2.find()) {
					//https://www.linkedin.com/feed/update/urn:li:activity:6994308509831995392/
					link = "https://www.linkedin.com/feed/update/urn:li:activity:" + matcher2.group(0).substring(29, matcher2.group(0).length()-1);

					Matcher matcher3 = pattern3.matcher(post);
					if (matcher3.find()) {
						text = matcher3.group(0).substring(49, matcher3.group(0).length()-15).replaceAll("\\\\uD[a-zA-Z0-9]{3}", " ").replaceAll("\\\\n", " ").replace("\\", "\\\\").replace("\\\"", "\"");
						//System.out.println(text);
						if(toAdd.containsKey(link)) {
							toAdd.put(link, new String[] {author, text});
						}
					}
				}
			}
			//"secondarySubtitle":{"textDirection":"USER_LOCALE","text":" to fetch age of post

		}
		sendToNotion(toAdd);
	}

	/**
	* Pushes the content of the LinkedHashMap to notion database 
	* It's done by sending POST request to notion API
	* They're inserted in a LinkedHashMap to avoid duplicates but the maintain 
	* order of appearance. It calls sendToNotion with the LInkedHashMap as param
	* 
	* @param   toAdd The posts to add, input is a LinkedHashMap
	*/
	public static void sendToNotion(LinkedHashMap<String, String[]> toAdd) throws IOException {

		URL url = new URL("https://api.notion.com/v1/pages");

		//Notion doesn't seem to allow creating many pages in one request so we need to loop
		for(HashMap.Entry<String,String[]> entry : toAdd.entrySet()) {

			HttpURLConnection httpConn = (HttpURLConnection) url.openConnection();
			//Populate the post request to write to Notion database
			httpConn.setRequestMethod("POST");

			httpConn.setRequestProperty("Notion-Version", "2022-06-28");
			httpConn.setRequestProperty("Content-Type", "application/json");
			httpConn.setRequestProperty("Authorization", NOTION_API_TOKEN);

			httpConn.setDoOutput(true);
			OutputStreamWriter writer = new OutputStreamWriter(httpConn.getOutputStream());

			StringBuilder toWrite = new StringBuilder("{\n    \"object\": \"page\",\n    \"parent\": {\n        \"type\": \"database_id\",\n        \"database_id\": \"" + NOTION_DATABASE_URL + "\"\n    },\n    \"archived\": false,\n");

			toWrite.append("\"properties\": {\n        \"Statut\": {\r\n"
					+ "            \"id\": \"nDsr\",\r\n"
					+ "            \"type\": \"select\",\r\n"
					+ "            \"select\": {\r\n"
					+ "                \"id\": \"xG<O\",\r\n"
					+ "                \"name\": \"À explorer\",\r\n"
					+ "                \"color\": \"brown\"\r\n"
					+ "            }\r\n"
					+ "        },\r\n"
					+ "    \"Auteur\": {\n            \"type\": \"rich_text\",\n            \"rich_text\": [\n                {\n                    \"type\": \"text\",\n                    \"text\": {\n                        \"content\": \"" + entry.getValue()[0] + "\"\n                    }\n                }\n            ]\n        },\n\n       \n        \"Name\": {\n            \"id\": \"title\",\n            \"type\": \"title\",\n            \"title\": [\n                {\n                    \"type\": \"text\",\n                    \"text\": {\n                        \"content\": \"" + entry.getValue()[0] + " - " + entry.getValue()[1].substring(0, Math.min(70, entry.getValue()[1].length())) + "\",\n                        \"link\": null\n                    },\n                    \"annotations\": {\n                        \"bold\": false,\n                        \"italic\": false,\n                        \"strikethrough\": false,\n                        \"underline\": false,\n                        \"code\": false,\n                        \"color\": \"default\"\n                    },\n                    \"plain_text\": \" " + entry.getValue()[0] + "\",\n                    \"href\": null\n                }\n            ]\n        },\n\t\t\"URL\": {\n\t\t\t\"url\": \"" + entry.getKey()+  "\"\n\t\t}\n    },\r\n \"children\": [{\r\n");
			
			//No more than 2000 characters allowed per block on notion so we might need to create more blocks
			for(int i=0; i<= entry.getValue()[1].length() / 2000; i++) {

				String per2000 = entry.getValue()[1].substring(i*2000,Math.min(entry.getValue()[1].length()-1,(i+1)*2000));
				if(i>0) {
					toWrite.append("},{");
				}
				toWrite.append("\"paragraph\": {\r\n"
						+ "    			\"rich_text\": [\r\n"
						+ "                    {\r\n"
						+ "                        \"type\": \"text\",\r\n"
						+ "                        \"text\": {\r\n"
						+ "                            \"content\": \""+ per2000 + "\"\r\n"
						+ "                        }\r\n"
						+ "                    }\r\n"
						+ "                ]\r\n"
						+ "\r\n"
						+ "    		  				}\r\n"
						+ "    			\r\n"
						+ "    		\r\n");
			}
			toWrite.append("}]\n}");
			writer.write(toWrite.toString());
			writer.flush();

			writer.close();
			httpConn.getOutputStream().close();

			InputStream responseStream = httpConn.getResponseCode() / 100 == 2
					? httpConn.getInputStream()
							: httpConn.getErrorStream();
			Scanner s = new Scanner(responseStream).useDelimiter("\\A");
			String response = s.hasNext() ? s.next() : "";
			//Prints Notion response to Console for visibility
			System.out.println(response);
			s.close();
		}			
	}

	
	/**
	 * Our main method. For now just calls the preferable login method (should use login with
	 * cookies to avoid triggering LinkedIn account security concerns) and then calls the parser.
	 * @param args The command line arguments.
	 * @throws java.io.IOException,InterruptedException just dump them.
	 **/
	public static void main(String[] args) throws IOException, InterruptedException {
		//Initialize connection to LiknedIn session and fetch doc
		Document doc = myLoginWithCookies("https://www.linkedin.com/my-items/saved-posts/");
		//Parse doc to fetch all link, author and text records
		parseSavedPosts(doc);

	}


	/**
	* It iterates through the doc and keeps a list of the unique saved post URNs
	* Compare each against the last file parsed in previous run (saved in a txt file)
	* to make sure we stop when we reach the last post we've already added.
	* 
	* @param   doc The document of page to parse
	* @return  toAdd A LinkedHashMap with unique post URNs
	*/
	public static LinkedHashMap<String, String[]> parseAndKeepEveryPostUrn(String doc) throws IOException {

		//Read from file the last post added on previous run to avoid pushing duplicates
		String latestAdded = "";
		try {
			File myObj = new File("lastPostAdded.txt");
			Scanner myReader = new Scanner(myObj);

			latestAdded = myReader.hasNextLine() ?  myReader.nextLine() : "nothing" ;
			myReader.close();
			//			System.out.println("Last Added is : " + lastAdded);
		} catch (FileNotFoundException e) {
			System.out.println("An error occurred.");
			e.printStackTrace();
		}

		LinkedHashMap<String, String[]> toAdd = new LinkedHashMap<String, String[]>();
		Pattern pattern = Pattern.compile("urn:li:activity:(.*?)[,?\"\\)]");
		Matcher matcher = pattern.matcher(doc);
		int count = 0;
		while (matcher.find()) {
			String link = "https://www.linkedin.com/feed/update/urn:li:activity:" + matcher.group(0).substring(16, matcher.group(0).length()-1);
			if(link.equals(latestAdded)) { //This will fail if the latestAdded post is deleted from saved posts on LinkedIn and will add everything
				break;
			}
			if(count++ == 0) {
				keepLatestPostUrn(link);
			}

			toAdd.put(link, null);
		}
		return toAdd;
	}

	
	/**
	* Write to file the last post added so we know where to stop next time we run the code
	* and thus avoid pushing duplicates
	* Current implementation will fail if we delete from saved posts on LinkedIn the one 
	* that matches the content of the file.
	* To manipulate this manually edit/delete the file
	* 
	* @param   latestEntryToPutInFile The latest URN added in Notion on last execution of the code
	*/
	public static void keepLatestPostUrn(String latestEntryToPutInFile) throws IOException{

		//Write in a file which was the last post added to know next time we run the code
		if (!latestEntryToPutInFile.equals("")){
			BufferedWriter writerToFile = new BufferedWriter(new FileWriter("lastPostAdded.txt"));
			writerToFile.write(latestEntryToPutInFile);
			writerToFile.close();
		}
	}
}
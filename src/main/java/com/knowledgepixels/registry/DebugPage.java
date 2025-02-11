package com.knowledgepixels.registry;

import static com.knowledgepixels.registry.RegistryDB.collection;
import static com.knowledgepixels.registry.RegistryDB.mongoSession;
import static com.mongodb.client.model.Indexes.ascending;

import java.io.IOException;

import org.bson.Document;

import com.mongodb.client.MongoCursor;

import io.vertx.ext.web.RoutingContext;

public class DebugPage extends Page {

	public static void show(RoutingContext context) {
		DebugPage page;
		try {
			page = new DebugPage(context);
			page.show();
		} catch (IOException ex) {
			ex.printStackTrace();
		} finally {
			context.response().end();
			// TODO Clean-up here?
		}
	}

	private DebugPage(RoutingContext context) {
		super(context);
	}

	protected void show() throws IOException {
		RoutingContext c = getContext();

		if (getRequestString().matches("/debug/trustPaths")) {
			String counterString = c.request().getParam("trustStateCounter");
			if (counterString == null) {
				print(getTrustPathsTxt());
			} else {
				Long counter = Long.parseLong(counterString);
				print(RegistryDB.getOne("debug_trustPaths", new Document("trustStateCounter", counter)).getString("trustStateTxt"));
			}
			c.response().putHeader("Content-Type", "text/plain");
		} else if (getRequestString().matches("/debug/endorsements")) {
			MongoCursor<Document> tp = collection("endorsements").find(mongoSession).cursor();
			while (tp.hasNext()) {
				Document d = tp.next();
				println(d.get("agent") + ">" + d.get("pubkey") + " " + d.get("endorsedNanopub") + " " + d.get("source") + " (" + d.get("status") + ")");
			}
			c.response().putHeader("Content-Type", "text/plain");
		} else if (getRequestString().matches("/debug/accounts")) {
			MongoCursor<Document> tp = collection("accounts").find(mongoSession).cursor();
			while (tp.hasNext()) {
				Document d = tp.next();
				println(d.getString("agent") + ">" + d.get("pubkey") + " " + d.get("depth") + " (" + d.get("status") + ")");
			}
		} else {
			c.response().setStatusCode(400).setStatusMessage("Invalid request: " + getFullRequest());
			return;
		}
	}

	public static String getTrustPathsTxt() {
		String s = "";
		MongoCursor<Document> tp = collection("trustPaths").find(mongoSession).sort(ascending("_id")).cursor();
		while (tp.hasNext()) {
			Document d = tp.next();
			String path = d.getString("_id");
			path = path.replace(" ", " > ");
			if (d.getString("type").equals("extended")) {
				path = path.replaceFirst(" > ([^ ]+)$", " ~ $1");
			}
			s += path + "\n";
		}
		return s;
	}

}

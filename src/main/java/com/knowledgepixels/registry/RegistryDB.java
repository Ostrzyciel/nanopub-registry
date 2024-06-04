package com.knowledgepixels.registry;

import java.security.GeneralSecurityException;

import org.bson.Document;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.nanopub.Nanopub;
import org.nanopub.NanopubUtils;
import org.nanopub.extra.security.MalformedCryptoElementException;
import org.nanopub.extra.security.NanopubSignatureElement;
import org.nanopub.extra.security.SignatureUtils;

import com.mongodb.BasicDBObject;
import com.mongodb.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;

import net.trustyuri.TrustyUriUtils;

public class RegistryDB {

	private RegistryDB() {}

	private static MongoClient mongoClient;
	private static MongoDatabase mongoDB;

	public static MongoDatabase getDB() {
		return mongoDB;
	}

	public static MongoCollection<Document> collection(String name) {
		return mongoDB.getCollection(name);
	}

	public static void init() {
		if (mongoClient != null) return;
		mongoClient = new MongoClient("mongodb");
		mongoDB = mongoClient.getDatabase("nanopub-registry");

		if (isInitialized()) return;

		collection("tasks").createIndex(Indexes.descending("not-before"));

		collection("nanopubs").createIndex(Indexes.ascending("full-id"), new IndexOptions().unique(true));
		collection("nanopubs").createIndex(Indexes.descending("counter"), new IndexOptions().unique(true));
		collection("nanopubs").createIndex(Indexes.ascending("pubkey"));

		collection("lists").createIndex(Indexes.ascending("pubkey", "type"), new IndexOptions().unique(true));
		collection("lists").createIndex(Indexes.ascending("status"));

		collection("list-entries").createIndex(Indexes.descending("pubkey", "type", "position"), new IndexOptions().unique(true));
		collection("list-entries").createIndex(Indexes.descending("type", "checksum"), new IndexOptions().unique(true));

		collection("loose-entries").createIndex(Indexes.ascending("pubkey"));
		collection("loose-entries").createIndex(Indexes.ascending("type"));

		collection("base-agents").createIndex(Indexes.ascending("agent", "pubkey"), new IndexOptions().unique(true));

		collection("trust-edges").createIndex(Indexes.ascending("from", "to"), new IndexOptions().unique(true));
	}

	public static boolean isInitialized() {
		return get("server-info", "setup-id") != null;
	}

	public static void increateStateCounter() {
		MongoCursor<Document> cursor = collection("server-info").find(new BasicDBObject("_id", "state-counter")).cursor();
		if (cursor.hasNext()) {
			long counter = cursor.next().getLong("value");
			collection("server-info").updateOne(new BasicDBObject("_id", "state-counter"), new BasicDBObject("$set", new BasicDBObject("value", counter + 1)));
		} else {
			collection("server-info").insertOne(new Document("_id", "state-counter").append("value", 0l));
		}
	}

	public static Object get(String collection, String elementName) {
		MongoCursor<Document> cursor = collection(collection).find(new BasicDBObject("_id", elementName)).cursor();
		if (!cursor.hasNext()) return null;
		return cursor.next().get("value");
	}

	public static Object getFirstField(String collection, String fieldName) {
		MongoCursor<Document> cursor = collection(collection).find().sort(new BasicDBObject("counter", -1)).cursor();
		if (!cursor.hasNext()) return null;
		return cursor.next().get(fieldName);
	}

	public static void set(String collection, String elementId, Object value) {
		MongoCursor<Document> cursor = collection(collection).find(new BasicDBObject("_id", elementId)).cursor();
		if (cursor.hasNext()) {
			collection(collection).updateOne(new BasicDBObject("_id", elementId), new BasicDBObject("$set", new BasicDBObject("value", value)));
		} else {
			collection(collection).insertOne(new Document("_id", elementId).append("value", value));
		}
	}

	public static void add(String collection, Document doc) {
		collection(collection).insertOne(doc);
	}

	public static MongoCursor<Document> get(String collection) {
		return collection(collection).find().sort(new BasicDBObject("agent", 1)).cursor();
	}

	public static void loadNanopub(Nanopub nanopub) {
		NanopubSignatureElement el = null;
		try {
			el = SignatureUtils.getSignatureElement(nanopub);
		} catch (MalformedCryptoElementException ex) {
			ex.printStackTrace();
		}
		if (!hasValidSignature(el)) {
			return;
		}
		Long counter = (Long) getFirstField("nanopubs", "counter");
		if (counter == null) counter = 0l;
		collection("nanopubs").insertOne(
				new Document("_id", TrustyUriUtils.getArtifactCode(nanopub.getUri().stringValue()))
					.append("full-id", nanopub.getUri().stringValue())
					.append("counter", counter + 1)
					.append("pubkey", el.getPublicKeyString())
					.append("content", NanopubUtils.writeToString(nanopub, RDFFormat.TRIG))
			);
	}

	private static boolean hasValidSignature(NanopubSignatureElement el) {
		try {
			if (el != null && SignatureUtils.hasValidSignature(el) && el.getPublicKeyString() != null) {
				return true;
			}
		} catch (GeneralSecurityException ex) {
			System.err.println("Error for signature element " + el.getUri());
			ex.printStackTrace();
		}
		return false;
	}

}

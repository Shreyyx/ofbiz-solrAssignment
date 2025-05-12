package org.apache.ofbiz.solr.indexing;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.ServiceUtil;
import javax.xml.namespace.QName;
import javax.xml.stream.events.Attribute;
import org.apache.ofbiz.base.util.UtilMisc;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.service.GenericServiceException;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.Characters;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.entity.Delegator;
import javax.xml.stream.events.StartElement;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilGenerics;
import java.util.function.BiFunction;
import java.util.List;
import java.util.Map;
import javax.xml.stream.events.XMLEvent;
import java.io.File;
import java.io.FileInputStream;
import java.math.BigDecimal;
import java.io.InputStream;
import java.util.*;
import java.sql.Timestamp;

import java.io.IOException;
import org.apache.ofbiz.base.util.UtilProperties;
import org.apache.ofbiz.solr.SolrUtil;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.client.solrj.request.UpdateRequest;

public class XmlParsing {
    public static final String MODULE = XmlParsing.class.getName();
    private static final String RESOURCE = "SolrUiLabels";

    public static Map<String, Object> parseXml(DispatchContext dctx, Map<String, Object> context) {
        String filePath = (String) context.get("filePath");
        Debug.logInfo("Processing XML file: " + filePath, MODULE);
        List<Map<String, Object>> itemsList = new ArrayList<>();
        boolean insideItems = false, insideItem = false;
        Map<String, Object> currentItem = null;
        int itemCount = 0;

        GenericValue userLogin = (GenericValue) context.get("userLogin");
        if (userLogin == null) {
            Debug.logError("User login is missing", MODULE);
            return ServiceUtil.returnError("User login is required.");
        }

        //class for reading binary data from files and creates an input stream that will read bytes from the file
        try (InputStream inputStream = new FileInputStream(new File(filePath))) {
            //it is the abstract factory class responsible for creating XML Stream readers
            //creates an instance of XMLInputFactory
            XMLInputFactory factory = XMLInputFactory.newInstance();
            //we use this created factory to create an event-based reader
            XMLEventReader reader = factory.createXMLEventReader(inputStream);

            while (reader.hasNext()) {
                XMLEvent event = reader.nextEvent();

                if (event.isStartElement()) {
                    StartElement startElement = event.asStartElement();
                    String tagName = startElement.getName().getLocalPart();
                    Debug.logInfo("Processing tag: " + tagName, MODULE);
                    if ("Items".equals(tagName)) {
                        insideItems = true;
                    } else if ("Item".equals(tagName) && insideItems) {
                        insideItem = true;
                        //create a new hashmap and store it in variable currentItem
                        currentItem = new HashMap<>();
                        //created an empty list to store it in the currentItem and to store multiple info
                        //currentItem.put temporarily holds all parsed data for that item
                        currentItem.put("packages", new ArrayList<>());
                        currentItem.put("descriptions", new ArrayList<>());
                        currentItem.put("extendedInformation", new ArrayList<>());
                        currentItem.put("productAttributes", new ArrayList<>());
                        currentItem.put("digitalAssets", new ArrayList<>());
                        currentItem.put("partInterchangeInfo", new ArrayList<>());
                        currentItem.put("prices", new ArrayList<>());
                        Debug.logInfo("New item started", MODULE);
                    } else if (insideItem) {
                        processItemAttributes(reader, tagName, currentItem);
                    }
                } else if (event.isEndElement()) {
                    String elementName = event.asEndElement().getName().getLocalPart();
                    if ("Item".equals(elementName) && insideItem) {
                        insideItem = false;
                        //objects.toString is used to handle nullPointerException; works as getOrDefault
                        String partNumber = Objects.toString(currentItem.get("PartNumber"), "");
                        String itemLevelGTIN = Objects.toString(currentItem.get("ItemLevelGTIN"), "");
                        String itemQuantitySize = Objects.toString(currentItem.get("ItemQuantitySize"), "");
                        String quantityPerApplication = Objects.toString(currentItem.get("QuantityPerApplication"), "");
                        String minimumOrderQuantity = Objects.toString(currentItem.get("MinimumOrderQuantity"), "");
                        String brandAAIAID = Objects.toString(currentItem.get("BrandAAIAID"), "");
                        String brandLabel = Objects.toString(currentItem.get("BrandLabel"), "");
                        String subBrandAAIAID = Objects.toString(currentItem.get("SubBrandAAIAID"), "");
                        String subBrandLabel = Objects.toString(currentItem.get("SubBrandLabel"), "");
                        String partTerminologyID = Objects.toString(currentItem.get("PartTerminologyID"),"");

                        //retrive details from the currentItem and then a list of maps is created because each tag here contains multiple entries
                        List<Map<String, String>> descriptions = (List<Map<String, String>>) currentItem.get("descriptions");
                        List<Map<String, String>> extendedInformation = (List<Map<String, String>>) currentItem.get("extendedInformation");
                        List<Map<String, String>> productAttributes = (List<Map<String, String>>) currentItem.get("productAttributes");
                        List<Map<String, String>> packages = (List<Map<String, String>>) currentItem.get("packages");
                        List<Map<String, String>> prices = (List<Map<String, String>>) currentItem.get("prices");
                        List<Map<String, String>> digitalAssets = (List<Map<String, String>>) currentItem.get("digitalAssets");
                        List<Map<String, String>> partInterchangeInfo = (List<Map<String, String>>) currentItem.get("partInterchangeInfo");

                        Debug.logInfo("Final descriptions for item: " + descriptions, MODULE);
                        Debug.logInfo("Final extended information for item: " + extendedInformation, MODULE);
                        Debug.logInfo("Final product attributes for item: " + productAttributes, MODULE);
                        Debug.logInfo("Final package details for item: " + packages, MODULE);
                        Debug.logInfo("Final price details for item: " + prices, MODULE);
                        Debug.logInfo("Final digital asset details for item: " + digitalAssets, MODULE);
                        Debug.logInfo("Final part interchange details for item: " + partInterchangeInfo, MODULE);
                        Map<String, Object> createdItem = createItem(dctx, partNumber, itemQuantitySize, quantityPerApplication, minimumOrderQuantity, itemLevelGTIN, brandAAIAID, brandLabel, partTerminologyID, subBrandAAIAID, subBrandLabel, descriptions, extendedInformation, productAttributes, packages, prices, digitalAssets, partInterchangeInfo, userLogin);
                        Debug.logInfo("Create Item Response: " + createdItem, MODULE);
                        itemsList.add(currentItem);
                        itemCount++;
                        Debug.logInfo("Item parsed successfully: " + currentItem, MODULE);
                    } else if ("Items".equals(elementName)) {
                        insideItems = false;
                        break;
                    }
                }
            }
            reader.close();
        } catch (Exception e) {
            Debug.logError(e, "Error parsing XML file: " + e.getMessage(), MODULE);
            return ServiceUtil.returnError("Error parsing XML file: " + e.getMessage());
        }
        Debug.logInfo("Total items found: " + itemCount, MODULE);

        Locale locale = (Locale) context.get("locale");
        Map<String, Object> solrContext = UtilMisc.toMap(
                "itemsList", itemsList,
                "indexName", "new_core",
                "locale", locale
        );
        Map<String, Object> solrResponse;
        try {
            solrResponse = sendItemsToSolr(dctx, solrContext);
            if (!ServiceUtil.isSuccess(solrResponse)) {
                Debug.logError("Failed to index items into Solr", MODULE);
            }
        } catch (GenericEntityException e) {
            Debug.logError(e, "Error sending documents to Solr", MODULE);
            return ServiceUtil.returnError("Error sending documents to Solr: " + e.getMessage());
        }

        Map<String, Object> result = ServiceUtil.returnSuccess();
        result.put("itemsList", itemsList);
        result.put("itemCount", itemCount);
        return result;
    }

    //XML Event Reader allows you to sequentially read events without loading the entire document into the memory

    public static Map<String, Object> sendItemsToSolr(DispatchContext dctx, Map<String, Object> context) throws GenericEntityException {
        List<Map<String, Object>> itemsList = (List<Map<String, Object>>) context.get("itemsList");
        String solrIndexName = (String) context.get("indexName");
        Locale locale = (Locale) context.get("locale");
        List<SolrInputDocument> solrDocs = new ArrayList<>();
        Map<String, Object> result;

        try (SolrClient solrClient = SolrUtil.getHttpSolrClient(solrIndexName)) {
            SolrUtil.getInstance();
            for (Map<String, Object> item : itemsList) {
                Object partNumber = item.get("PartNumber");
                if (partNumber == null || partNumber.toString().trim().isEmpty()) {
                    Debug.logWarning("Missing PartNumber in item: " + item, MODULE);
                    continue;
                }
                SolrInputDocument doc = new SolrInputDocument();
                doc.addField("productId", partNumber);
                doc.addField("idValue", item.get("ItemLevelGTIN"));
                doc.addField("piecesIncluded", item.get("ItemQuantitySize"));
                doc.addField("quantityIncluded", item.get("QuantityPerApplication"));
                doc.addField("orderDecimalQuantity", item.get("MinimumOrderQuantity"));
                doc.addField("brandAAIAID", item.get("BrandAAIAID"));
                doc.addField("brandLabel", item.get("BrandLabel"));
                doc.addField("subBrandAAIAID", item.get("SubBrandAAIAID"));
                doc.addField("subBrandLabel", item.get("SubBrandLabel"));
                doc.addField("partTerminologyID", item.get("PartTerminologyID"));

                List<Map<String, String>> descriptions = UtilGenerics.cast(item.get("descriptions"));
                StringBuilder descText = new StringBuilder();
                if (descriptions != null) {
                    Debug.logInfo("Descriptions found for PartNumber " + partNumber, MODULE);
                    for (Map<String, String> desc : descriptions) {
                        String code = desc.getOrDefault("DescriptionCode", "");
                        String lang = desc.getOrDefault("LanguageCode", "");
                        String seq = desc.getOrDefault("Sequence", "");
                        String text = desc.getOrDefault("Description", "");
                        if (!text.isEmpty()) {
                            if (descText.length() > 0) descText.append("; ");
                            descText.append(code)
                                    .append(" (").append(lang)
                                    .append(seq.isEmpty() ? "" : ", Seq=" + seq)
                                    .append("): ").append(text);
                        }
                    }
                } else {
                    Debug.logWarning("No descriptions found for PartNumber " + partNumber, MODULE);
                }
                String descriptionsStr = descText.toString();
                if (!descriptionsStr.isEmpty()) {
                    doc.addField("descriptions", descriptionsStr);
                }

                List<Map<String, String>> extendedInfo = UtilGenerics.cast(item.get("extendedInformation"));
                StringBuilder extText = new StringBuilder();
                if (extendedInfo != null) {
                    Debug.logInfo("ExtendedInformation found for PartNumber " + partNumber + ": " + extendedInfo.size(), MODULE);
                    for (Map<String, String> ext : extendedInfo) {
                        String expiCode = ext.getOrDefault("EXPICode", "");
                        String lang = ext.getOrDefault("LanguageCode", "");
                        String text = ext.getOrDefault("ExtendedProductInformation", "");
                        if (!text.isEmpty()) {
                                if (extText.length() > 0) extText.append("; ");
                                extText.append(expiCode).append(" - ").append(text);
                            }
                    }
                } else {
                    Debug.logWarning("No extendedInformation found for PartNumber " + partNumber, MODULE);
                }
                String extendedInfoStr = extText.toString();
                if (!extendedInfoStr.isEmpty()) {
                    doc.addField("extendedInformation", extendedInfoStr);
                }

                List<Map<String, String>> productAttrs = UtilGenerics.cast(item.get("productAttributes"));
                StringBuilder attrText = new StringBuilder();
                if (productAttrs != null) {
                    Debug.logInfo("ProductAttributes found for PartNumber " + partNumber + ": " + productAttrs.size(), MODULE);
                    for (Map<String, String> attr : productAttrs) {
                        String attrID = attr.getOrDefault("AttributeID", "");
                        String text = attr.getOrDefault("ProductAttribute", "");
                        if (!text.isEmpty()) {
                            if (attrText.length() > 0) attrText.append("; ");
                            attrText.append(attrID).append(": ").append(text);
                        }
                    }
                } else {
                    Debug.logWarning("No productAttributes found for PartNumber " + partNumber, MODULE);
                }
                if (!attrText.isEmpty()) {
                    doc.addField("productAttributes", attrText);
                }

                List<Map<String, String>> packages = UtilGenerics.cast(item.get("packages"));
                StringBuilder pkgText = new StringBuilder();
                if (packages != null) {
                    Debug.logInfo("Packages found for PartNumber " + partNumber + ": " + packages.size(), MODULE);
                    for (Map<String, String> pkg : packages) {
                        StringBuilder pkgDesc = new StringBuilder();
                        for (Map.Entry<String, String> entry : pkg.entrySet()) {
                            if (UtilValidate.isNotEmpty(entry.getValue())) {
                                if (pkgDesc.length() > 0) pkgDesc.append(", ");
                                pkgDesc.append(entry.getKey()).append("=").append(entry.getValue());
                            }
                        }
                        if (pkgDesc.length() > 0) {
                            if (pkgText.length() > 0) pkgText.append("; ");
                            pkgText.append(pkgDesc);
                        }
                    }
                } else {
                    Debug.logWarning("No packages found for PartNumber " + partNumber, MODULE);
                }
                String packagesStr = pkgText.toString();
                if (!packagesStr.isEmpty()) {
                    doc.addField("packages", packagesStr);
                }

                List<Map<String, String>> prices = UtilGenerics.cast(item.get("prices"));
                StringBuilder priceText = new StringBuilder();
                if (prices != null) {
                    Debug.logInfo("Prices found for PartNumber " + partNumber + ": " + prices.size(), MODULE);
                    for (Map<String, String> price : prices) {
                        StringBuilder priceDesc = new StringBuilder();
                        for (Map.Entry<String, String> entry : price.entrySet()) {
                            if (UtilValidate.isNotEmpty(entry.getValue())) {
                                if (priceDesc.length() > 0) priceDesc.append(", ");
                                priceDesc.append(entry.getKey()).append("=").append(entry.getValue());
                            }
                        }
                        if (priceDesc.length() > 0) {
                            if (priceText.length() > 0) priceText.append("; ");
                            priceText.append(priceDesc);
                        }
                    }
                } else {
                    Debug.logWarning("No prices found for PartNumber " + partNumber, MODULE);
                }
                String pricesStr = priceText.toString();
                if (!pricesStr.isEmpty()) {
                    doc.addField("prices", pricesStr);
                }

                List<Map<String, String>> digitalAssets = UtilGenerics.cast(item.get("digitalAssets"));
                StringBuilder assetText = new StringBuilder();
                if (digitalAssets != null) {
                    Debug.logInfo("DigitalAssets found for PartNumber " + partNumber + ": " + digitalAssets.size(), MODULE);
                    for (Map<String, String> asset : digitalAssets) {
                        StringBuilder assetDesc = new StringBuilder();
                        for (Map.Entry<String, String> entry : asset.entrySet()) {
                            if (UtilValidate.isNotEmpty(entry.getValue())) {
                                if (assetDesc.length() > 0) assetDesc.append(", ");
                                assetDesc.append(entry.getKey()).append("=").append(entry.getValue());
                            }
                        }
                        if (assetDesc.length() > 0) {
                            if (assetText.length() > 0) assetText.append("; ");
                            assetText.append(assetDesc);
                        }
                    }
                } else {
                    Debug.logWarning("No digitalAssets found for PartNumber " + partNumber, MODULE);
                }
                String digitalAssetsStr = assetText.toString();
                if (!digitalAssetsStr.isEmpty()) {
                    doc.addField("digitalAssets", digitalAssetsStr);
                }

                List<Map<String, String>> interchanges = UtilGenerics.cast(item.get("partInterchangeInfo"));
                StringBuilder interchangeText = new StringBuilder();
                if (interchanges != null) {
                    Debug.logInfo("PartInterchangeInfo found for PartNumber " + partNumber + ": " + interchanges.size(), MODULE);
                    for (Map<String, String> interchange : interchanges) {
                        StringBuilder interchangeDesc = new StringBuilder();
                        for (Map.Entry<String, String> entry : interchange.entrySet()) {
                            if (UtilValidate.isNotEmpty(entry.getValue())) {
                                if (interchangeDesc.length() > 0) interchangeDesc.append(", ");
                                interchangeDesc.append(entry.getKey()).append("=").append(entry.getValue());
                            }
                        }
                        if (interchangeDesc.length() > 0) {
                            if (interchangeText.length() > 0) interchangeText.append("; ");
                            interchangeText.append(interchangeDesc);
                        }
                    }
                } else {
                    Debug.logWarning("No partInterchangeInfo found for PartNumber " + partNumber, MODULE);
                }
                if (!interchangeText.isEmpty()) {
                    doc.addField("partInterchangeInfo", interchangeText);
                }

                List<SolrInputDocument> children = new ArrayList<>();
                if (descriptions != null) {
                    for (Map<String, String> description : descriptions) {
                        SolrInputDocument child = new SolrInputDocument();
                        child.addField("type", "description - " + partNumber);
                        child.addField("descriptionCode", description.get("DescriptionCode"));
                        child.addField("languageCode", description.get("LanguageCode"));
                        child.addField("descriptionText", description.get("Description"));
                        Debug.logInfo("Adding Description child doc for PartNumber " + partNumber + ": " + description, MODULE);
                        children.add(child);
                    }
                }
                if (extendedInfo != null) {
                    for (Map<String, String> ext : extendedInfo) {
                        SolrInputDocument child = new SolrInputDocument();
                        child.addField("type", "extendedInformation - " + partNumber);
                        child.addField("expiCode", ext.get("EXPICode"));
                        child.addField("extendedInfoText", ext.get("ExtendedProductInformation"));
                        Debug.logInfo("Adding Extended Info child doc for PartNumber " + partNumber + ": " + ext, MODULE);
                        children.add(child);
                    }
                }
                if (productAttrs != null) {
                    for (Map<String, String> attr : productAttrs) {
                        SolrInputDocument child = new SolrInputDocument();
                        child.addField("type", "productAttribute - " + partNumber);
                        child.addField("attributeID", attr.get("AttributeID"));
                        child.addField("attributeText", attr.get("ProductAttribute"));
                        Debug.logInfo("Adding Product Attribute child doc for PartNumber " + partNumber + ": " + attr, MODULE);
                        children.add(child);
                    }
                }
                if (packages != null) {
                    for (Map<String, String> pkg : packages) {
                        SolrInputDocument child = new SolrInputDocument();
                        child.addField("type", "package - " + partNumber);
                        for (Map.Entry<String, String> entry : pkg.entrySet()) {
                            child.addField("package_" + entry.getKey(), entry.getValue());
                        }
                        Debug.logInfo("Adding Package child doc for PartNumber " + partNumber + ": " + pkg, MODULE);
                        children.add(child);
                    }
                }
                if (prices != null) {
                    for (Map<String, String> price : prices) {
                        SolrInputDocument child = new SolrInputDocument();
                        child.addField("type", "price - " + partNumber);
                        child.addField("priceType", price.get("PriceType"));
                        child.addField("currencyCode", price.get("CurrencyCode"));
                        child.addField("priceValue", price.get("Price"));
                        Debug.logInfo("Adding Pricing child doc for PartNumber " + partNumber + ": " + price, MODULE);
                        children.add(child);
                    }
                }
                if (digitalAssets != null) {
                    for (Map<String, String> digitalAsset : digitalAssets) {
                        SolrInputDocument child = new SolrInputDocument();
                        child.addField("type", "digital-asset - " + partNumber);
                        for (Map.Entry<String, String> entry : digitalAsset.entrySet()) {
                            child.addField("digitalAsset_" + entry.getKey(), entry.getValue());
                        }
                        Debug.logInfo("Adding Digital Asset child doc for PartNumber " + partNumber + ": " + digitalAsset, MODULE);
                        children.add(child);
                    }
                }
                if (interchanges != null) {
                    for (Map<String, String> part : interchanges) {
                        SolrInputDocument child = new SolrInputDocument();
                        child.addField("type", "partInterchange - " + partNumber);
                        for (Map.Entry<String, String> entry : part.entrySet()) {
                            child.addField("interchange_" + entry.getKey(), entry.getValue());
                        }
                        Debug.logInfo("Adding PartInterchange child doc for PartNumber " + partNumber + ": " + part, MODULE);
                        children.add(child);
                    }
                }
                if (!children.isEmpty()) {
                    doc.addChildDocuments(children);
                }
                Debug.logInfo("Constructed Solr doc for PartNumber " + partNumber + " with " + children.size() + " children.", MODULE);
                solrDocs.add(doc);
            }
            if (!solrDocs.isEmpty()) {
                UpdateRequest updateRequest = new UpdateRequest();
                updateRequest.add(solrDocs);
                updateRequest.process(solrClient);
                solrClient.commit();
                Debug.logInfo("Successfully indexed " + solrDocs.size() + " documents to Solr.", MODULE);
            }
            final String statusStr = UtilProperties.getMessage(RESOURCE, "SolrDocumentsAddedToSolrIndex",
                    UtilMisc.toMap("documentCount", solrDocs.size()), locale);
            result = ServiceUtil.returnSuccess(statusStr);
        } catch (Exception e) {
            Debug.logError(e, "Error sending documents to Solr", MODULE);
            result = ServiceUtil.returnError("Error sending documents to Solr: " + e.getMessage());
        }
        return result;
    }

    private static void processItemAttributes(XMLEventReader reader, String tagName, Map<String, Object> currentItem) throws Exception {
        Debug.logInfo("Processing attribute: " + tagName, MODULE);
        //used to store values of these tags if these tags are encountered
        //list.of()--> creates an immutable list from the given elements , we can also use or symbol "||"
        //we can also use Arrays.asList here to create a mutable list
        if (List.of("PartNumber", "ItemLevelGTIN", "ItemQuantitySize", "QuantityPerApplication", "MinimumOrderQuantity", "BrandAAIAID", "BrandLabel", "SubBrandAAIAID", "SubBrandLabel", "PartTerminologyID").contains(tagName)) {
            XMLEvent nextEvent = reader.nextEvent();
            if (nextEvent.isCharacters()) { //isCharacter checks if the next event is a text
                String value = nextEvent.asCharacters().getData().trim(); //extracts the actual text from XML tag
                //used if partNumber is not stored it stores or else it does not, it check whether it is stored in the cuurentItem to store it just once for that item
                if ("PartNumber".equals(tagName) && !currentItem.containsKey("PartNumber")) {
                    currentItem.put("PartNumber", value);
                    Debug.logInfo("Stored PartNumber: " + value, MODULE);
                } else if (!"PartNumber".equals(tagName)) {
                    currentItem.put(tagName, value);
                    Debug.logInfo("Stored " + tagName + ": " + value, MODULE);
                }
            }
        } else if ("ProductAttributes".equals(tagName)) {
            //we pass XML stream reader here, gets product attribute list from the currentItem
            processProductAttribute(reader, (List<Map<String, String>>) currentItem.get("productAttributes"), currentItem);
        } else if ("Descriptions".equals(tagName)) {
            processDescription(reader, (List<Map<String, String>>) currentItem.get("descriptions"), currentItem);
        } else if ("ExtendedInformation".equals(tagName)) {
            processExtendedProductInformation(reader, (List<Map<String, String>>) currentItem.get("extendedInformation"), currentItem);
        } else if ("Packages".equals(tagName)) {
            List<Map<String, String>> packages = processPackages(reader);
            if (currentItem == null) {
                currentItem = new HashMap<>(); //avoids null pointer exception
            }
            currentItem.put("packages", packages);
        } else if ("Prices".equals(tagName)) {
            List<Map<String, String>> prices = processPrices(reader);
            if (currentItem == null) {
                currentItem = new HashMap<>();
            }
            currentItem.put("prices", prices);
        } else if ("DigitalAssets".equals(tagName)) {
            List<Map<String, String>> digitalAssets = processDigitalAssets(reader);
            if (currentItem == null) {
                currentItem = new HashMap<>();
            }
            currentItem.put("digitalAssets", digitalAssets);
        } else if ("PartInterchangeInfo".equals(tagName)) {
            List<Map<String, String>> partInterchangeList = processPartInterchangeInfo(reader, currentItem);
            if (currentItem == null) {
                currentItem = new HashMap<>();
            }
            currentItem.put("partInterchangeInfo", partInterchangeList);
        }
    }

    public static void processProductAttribute(XMLEventReader reader, List<Map<String, String>> productAttributeList, Map<String, Object> currentItem) throws Exception {
        Debug.logInfo("Processing Product Attribute", MODULE);

        if (!currentItem.containsKey("productAttributes")) {
            //if nothing is present in the list it will initialize a new list
            currentItem.put("productAttributes", new ArrayList<>());
        }

        //retrieves the existing list to ensure that all attributes are added to the same list
        List<Map<String, String>> existingProductAttributes = (List<Map<String, String>>) currentItem.get("productAttributes");

        while (reader.hasNext()) {
            XMLEvent event = reader.nextEvent();

            if (event.isStartElement() && "ProductAttribute".equals(event.asStartElement().getName().getLocalPart())) {
                StartElement startElement = event.asStartElement();
                Attribute attributeIDAttr = startElement.getAttributeByName(new QName("AttributeID"));
                //checks if the attribute it not null, else just returns an empty string
                String attributeID = (attributeIDAttr != null) ? attributeIDAttr.getValue() : "";
                //String builder improves efficiency, since strings are immutable in java each modification will create a new string, String Builder avoids this overhead
                StringBuilder productAttributeText = new StringBuilder();

                while (reader.hasNext()) {
                    event = reader.nextEvent();
                    if (event.isCharacters()) {
                        //will add the extracted text to the end of product attribute
                        productAttributeText.append(event.asCharacters().getData().trim()).append(" ");
                    } else if (event.isEndElement() && "ProductAttribute".equals(event.asEndElement().getName().getLocalPart())) {
                        break;
                    }
                }
                String finalProductAttributeText = productAttributeText.toString().trim();
                Map<String, String> productAttributeData = new HashMap<>();
                productAttributeData.put("AttributeID", attributeID);
                productAttributeData.put("ProductAttribute", finalProductAttributeText);
                existingProductAttributes.add(productAttributeData);
                Debug.logInfo("Added product attributes: " + productAttributeData, MODULE);
            } else if (event.isEndElement() && "ProductAttributes".equals(event.asEndElement().getName().getLocalPart())) {
                break;
            }
        }
    }

    public static void processExtendedProductInformation(XMLEventReader reader, List<Map<String, String>> extendedProductInformationList, Map<String, Object> currentItem) throws Exception {
        Debug.logInfo("Processing Extended Product Information", MODULE);

        //it checks if the currentItem map already has a key called ExtendedInformation and if it does not it initializes a new empty list
        if (!currentItem.containsKey("extendedInformation")) {
            currentItem.put("extendedInformation", new ArrayList<>());
        }

        List<Map<String, String>> existingextendedInformation = (List<Map<String, String>>) currentItem.get("extendedInformation");

        while (reader.hasNext()) {
            XMLEvent event = reader.nextEvent();

            if (event.isStartElement() && "ExtendedProductInformation".equals(event.asStartElement().getName().getLocalPart())) {
                StartElement startElement = event.asStartElement();
                Attribute expiCodeAttr = startElement.getAttributeByName(new QName("EXPICode"));
                String expiCode = (expiCodeAttr != null) ? expiCodeAttr.getValue() : "";
                StringBuilder extendedProductInformationText = new StringBuilder();

                while (reader.hasNext()) {
                    event = reader.nextEvent();
                    if (event.isCharacters()) {
                        extendedProductInformationText.append(event.asCharacters().getData().trim()).append(" ");
                    } else if (event.isEndElement() && "ExtendedProductInformation".equals(event.asEndElement().getName().getLocalPart())) {
                        break;
                    }
                }
                String finalExtendedProductInformationText = extendedProductInformationText.toString().trim();
                Map<String, String> extendedProductInformationData = new HashMap<>();
                extendedProductInformationData.put("EXPICode", expiCode);
                extendedProductInformationData.put("ExtendedProductInformation", finalExtendedProductInformationText);
                existingextendedInformation.add(extendedProductInformationData);
                Debug.logInfo("Added extended product information: " + extendedProductInformationData, MODULE);
            } else if (event.isEndElement() && "ExtendedInformation".equals(event.asEndElement().getName().getLocalPart())) {
                break;
            }
        }
    }

    public static void processDescription(XMLEventReader reader, List<Map<String, String>> descriptionList, Map<String, Object> currentItem) throws Exception {
        Debug.logInfo("Processing Descriptions", MODULE);

        List<Map<String, String>> existingDescriptions = (List<Map<String, String>>) currentItem.get("descriptions");

        if (existingDescriptions == null) {
            existingDescriptions = new ArrayList<>();
            currentItem.put("descriptions", existingDescriptions);
        }

        while (reader.hasNext()) {
            XMLEvent event = reader.nextEvent();

            if (event.isStartElement() && "Description".equals(event.asStartElement().getName().getLocalPart())) {
                StartElement startElement = event.asStartElement();
                Attribute descriptionCodeAttr = startElement.getAttributeByName(new QName("DescriptionCode"));
                Attribute languageCodeAttr = startElement.getAttributeByName(new QName("LanguageCode"));
                String descriptionCode = (descriptionCodeAttr != null) ? descriptionCodeAttr.getValue() : "";
                String languageCode = (languageCodeAttr != null) ? languageCodeAttr.getValue() : "";
                //string builder is used to build the final description string by appending text chunks, used to accumulate text content
                StringBuilder descriptionText = new StringBuilder();

                while (reader.hasNext()) {
                    event = reader.nextEvent();
                    if (event.isCharacters()) {
                        //keeps on appending characters until it reaches the end of description tag
                        descriptionText.append(event.asCharacters().getData().trim()).append(" ");
                    } else if (event.isEndElement() && "Description".equals(event.asEndElement().getName().getLocalPart())) {
                        break;
                    }
                }
                String finalText = descriptionText.toString().trim();
                Map<String, String> descriptionData = new HashMap<>();
                descriptionData.put("DescriptionCode", descriptionCode);
                descriptionData.put("LanguageCode", languageCode);
                descriptionData.put("Description", finalText);
                existingDescriptions.add(descriptionData);
                Debug.logInfo("Added description: " + descriptionData, MODULE);
            } else if (event.isEndElement() && "Descriptions".equals(event.asEndElement().getName().getLocalPart())) {
                break;
            }
        }
        currentItem.put("descriptions", existingDescriptions);
    }

    private static List<Map<String, String>> processPackages(XMLEventReader reader) throws XMLStreamException {
        List<Map<String, String>> packages = new ArrayList<>();
        Map<String, String> currentPackage = null;
        boolean insideDimensions = false;
        boolean insideWeights = false;

        while (reader.hasNext()) {
            XMLEvent event = reader.nextEvent();
            if (event.isStartElement()) {
                String tagName = event.asStartElement().getName().getLocalPart();
                switch (tagName) {
                    case "Package":
                        currentPackage = new HashMap<>();
                        break;
                    case "PackageLevelGTIN":
                    case "PackageBarCodeCharacters":
                    case "MerchandisingHeight":
                    case "MerchandisingWidth":
                    case "MerchandisingLength":
                    case "ShippingHeight":
                    case "ShippingWidth":
                    case "ShippingLength":
                    case "Weight":
                    case "PackageUOM":
                        currentPackage.put(tagName, getCharacterData(reader));
                        break;
                    case "InnerQuantity":
                        currentPackage.put("InnerQuantity", getCharacterData(reader));
                        break;
                    case "Dimensions":
                        insideDimensions = true;
                        break;
                    case "Weights":
                        insideWeights = true;
                        break;
                }
            } else if (event.isEndElement()) {
                String tagName = event.asEndElement().getName().getLocalPart();
                if ("Dimensions".equals(tagName)) {
                    insideDimensions = false;
                } else if ("Weights".equals(tagName)) {
                    insideWeights = false;
                } else if ("Package".equals(tagName) && currentPackage != null) {
                    packages.add(currentPackage);
                    currentPackage = null;
                } else if ("Packages".equals(tagName)) {
                    break;
                }
            }
        }
        return packages;
    }

    private static List<Map<String, String>> processDigitalAssets(XMLEventReader reader) throws XMLStreamException {
        List<Map<String, String>> digitalAssets = new ArrayList<>();
        Map<String, String> currentDigitalAsset = null;
        boolean insideAssetDimensions = false;

        while (reader.hasNext()) {
            XMLEvent event = reader.nextEvent();

            if (event.isStartElement()) {
                String tagName = event.asStartElement().getName().getLocalPart();
                switch (tagName) {
                    case "DigitalFileInformation":
                        currentDigitalAsset = new HashMap<>();
                        currentDigitalAsset.put("LanguageCode", getAttributeValue(event.asStartElement(), "LanguageCode"));
                        currentDigitalAsset.put("MaintenanceType", getAttributeValue(event.asStartElement(), "MaintenanceType"));
                        break;

                    case "FileName":
                    case "AssetType":
                    case "FileType":
                    case "Representation":
                    case "Background":
                    case "OrientationView":
                    case "URI":
                        currentDigitalAsset.put(tagName, getCharacterData(reader));
                        break;

                    case "AssetDimensions":
                        insideAssetDimensions = true;
                        currentDigitalAsset.put("AssetUOM", getAttributeValue(event.asStartElement(), "UOM"));
                        break;
                    case "AssetHeight":
                        if (insideAssetDimensions) {
                            currentDigitalAsset.put("AssetHeight", getCharacterData(reader));
                        }
                        break;
                    case "AssetWidth":
                        if (insideAssetDimensions) {
                            currentDigitalAsset.put("AssetWidth", getCharacterData(reader));
                        }
                        break;
                }

            } else if (event.isEndElement()) {
                String tagName = event.asEndElement().getName().getLocalPart();

                if ("AssetDimensions".equals(tagName)) {
                    insideAssetDimensions = false;
                } else if ("DigitalFileInformation".equals(tagName) && currentDigitalAsset != null) {
                    digitalAssets.add(currentDigitalAsset);
                    currentDigitalAsset = null;
                } else if ("DigitalAssets".equals(tagName)) {
                    break;
                }
            }
        }
        return digitalAssets;
    }

    private static List<Map<String, String>> processPrices(XMLEventReader reader) throws XMLStreamException {
        List<Map<String, String>> prices = new ArrayList<>();
        Map<String, String> currentPrice = null;

        while (reader.hasNext()) {
            XMLEvent event = reader.nextEvent();
            if (event.isStartElement()) {
                String tagName = event.asStartElement().getName().getLocalPart();
                switch (tagName) {
                    case "Pricing":
                        currentPrice = new HashMap<>();
                        String priceType = getAttributeValue(event.asStartElement(), "PriceType");
                        currentPrice.put("PriceType", priceType);
                        break;
                    case "CurrencyCode":
                        currentPrice.put("CurrencyCode", getCharacterData(reader));
                        break;
                    case "Price":
                        currentPrice.put("Price", getCharacterData(reader));
                        break;
                }
            } else if (event.isEndElement()) {
                String tagName = event.asEndElement().getName().getLocalPart();
                if ("Pricing".equals(tagName) && currentPrice != null) {
                    prices.add(currentPrice);
                } else if ("Prices".equals(tagName)) {
                    break;
                }
            }
        }
        return prices;
    }

    private static List<Map<String, String>> processPartInterchangeInfo(XMLEventReader reader, Map<String, Object> currentItem) throws XMLStreamException {
        List<Map<String, String>> partInterchangeList = new ArrayList<>();

        while (reader.hasNext()) {
            XMLEvent event = reader.nextEvent();

            if (event.isStartElement()) {
                String tagName = event.asStartElement().getName().getLocalPart();

                if ("PartInterchange".equals(tagName)) {
                    Map<String, String> interchangeMap = new HashMap<>();
                    StartElement partInterchange = event.asStartElement();
                    interchangeMap.put("partBrandAAIAID", getAttributeValue(partInterchange, "BrandAAIAID"));
                    interchangeMap.put("partBrandLabel", getAttributeValue(partInterchange, "BrandLabel"));
                    interchangeMap.put("ItemEquivalentUOM", getAttributeValue(partInterchange, "ItemEquivalentUOM"));
                    interchangeMap.put("productId", (String) currentItem.get("PartNumber"));

                    while (reader.hasNext()) {
                        event = reader.nextEvent();

                        if (event.isStartElement() && "PartNumber".equals(event.asStartElement().getName().getLocalPart())) {
                            StartElement partNumberElement = event.asStartElement();
                            interchangeMap.put("InterchangeQuantity", getAttributeValue(partNumberElement, "InterchangeQuantity"));

                            String partNumberTo = getCharacterData(reader);
                            interchangeMap.put("PartNumberTo", partNumberTo);
                        } else if (event.isEndElement() && "PartInterchange".equals(event.asEndElement().getName().getLocalPart())) {
                            break;
                        }
                    }
                    partInterchangeList.add(interchangeMap);
                }

            } else if (event.isEndElement() && "PartInterchangeInfo".equals(event.asEndElement().getName().getLocalPart())) {
                break;
            }
        }
        return partInterchangeList;
    }

    private static Map<String, Object> createItem(DispatchContext dctx, String partNumber, String itemQuantitySize, String quantityPerApplication, String minimumOrderQuantity, String itemLevelGTIN, String brandAAIAID, String brandLabel, String partTerminologyID, String subBrandAAIAID, String subBrandLabel, List<Map<String, String>> descriptions, List<Map<String, String>> extendedInformation, List<Map<String, String>> productAttributes, List<Map<String, String>> packageDetails, List<Map<String, String>> priceDetail, List<Map<String, String>> digitalAssetDetail, List<Map<String, String>> partInterchangeDetail, GenericValue userLogin) {

        Map<String, Object> response = new HashMap<>();
        try {
            GenericValue existingProduct = EntityQuery.use(dctx.getDelegator())
                    .from("Product")
                    .where("productId", partNumber)
                    .queryOne();

            Map<String, Object> productParams = UtilMisc.toMap(
                    "productId", partNumber,
                    "productTypeId", "FINISHED_GOOD",
                    "internalName", partNumber,
                    "piecesIncluded", itemQuantitySize,
                    "quantityIncluded", quantityPerApplication,
                    "orderDecimalQuantity", minimumOrderQuantity,
                    "userLogin", userLogin
            );

            if (existingProduct != null) {
                Debug.logInfo("Product exists. Updating product: " + partNumber, MODULE);
                Map<String, Object> updateResult = dctx.getDispatcher().runSync("updateProduct", productParams);
                updateItemLevelGTIN(dctx, partNumber, itemLevelGTIN, userLogin);
                updateProductCategory(dctx, brandAAIAID, brandLabel, partTerminologyID, subBrandAAIAID, subBrandLabel, partNumber, userLogin);

                if (descriptions != null) {
                    for (Map<String, String> desc : descriptions) {
                        String languageCode = desc.getOrDefault("LanguageCode", "");
                        String descriptionCode = desc.getOrDefault("DescriptionCode", "");
                        String finalText = desc.getOrDefault("Description", "");
                        updateDescription(dctx, descriptionCode, languageCode, finalText, partNumber, userLogin);
                    }
                }

                if (extendedInformation != null) {
                    for (Map<String, String> extInfo : extendedInformation) {
                        String expiCode = extInfo.getOrDefault("EXPICode", "");
                        String finalExtendedProductInformationText = extInfo.getOrDefault("ExtendedProductInformation", "");
                        updateExtendedProductInformation(dctx, expiCode, finalExtendedProductInformationText, partNumber, userLogin);
                    }
                }

                if (priceDetail != null) {
                    for (Map<String, String> priceInfo : priceDetail) {
                        String priceType = priceInfo.getOrDefault("PriceType", "");
                        String currencyCode = priceInfo.getOrDefault("CurrencyCode", "");
                        String price = priceInfo.getOrDefault("Price", "");
                        createPrices(dctx, partNumber, priceType, price, userLogin);
                    }
                }

                if (productAttributes != null) {
                    for (Map<String, String> attrInfo : productAttributes) {
                        String attributeId = attrInfo.getOrDefault("AttributeID", "");
                        String finalProductAttributeText = attrInfo.getOrDefault("ProductAttribute", "");
                        updateProductAttribute(dctx, partNumber, attributeId, finalProductAttributeText, userLogin);
                    }
                }

                if (packageDetails != null) {
                    updatePackages(dctx, packageDetails, partNumber, userLogin);
                }
                if (digitalAssetDetail != null) {
                    for (Map<String, String> digitalAssetInfo : digitalAssetDetail) {
                        String languageCode = digitalAssetInfo.getOrDefault("LanguageCode", "");
                        String FileName = digitalAssetInfo.getOrDefault("FileName", "");
                        String assetType = digitalAssetInfo.getOrDefault("AssetType", "");
                        String representation = digitalAssetInfo.getOrDefault("Representation", "");
                        String background = digitalAssetInfo.getOrDefault("Background", "");
                        String orientationView = digitalAssetInfo.getOrDefault("OrientationView", "");
                        String assetHeight = digitalAssetInfo.getOrDefault("AssetHeight", "");
                        String assetWidth = digitalAssetInfo.getOrDefault("AssetWidth", "");
                        String FileType = digitalAssetInfo.getOrDefault("FileType", "");
                        String uri = digitalAssetInfo.getOrDefault("URI", "");

                        GenericValue existingResource = EntityQuery.use(dctx.getDelegator())
                                .from("DataResource")
                                .where("dataResourceName", FileName)
                                .queryFirst();
                        if (existingResource != null) {
                            String dataResourceId = existingResource.getString("dataResourceId");
                            Debug.logInfo("==== dataResourceid ====" + dataResourceId, MODULE);
                            Map<String, String> attributes = new HashMap<>();
                            attributes.put("AssetType", assetType);
                            attributes.put("Representation", representation);
                            attributes.put("Background", background);
                            attributes.put("OrientationView", orientationView);
                            attributes.put("AssetHeight", assetHeight);
                            attributes.put("AssetWidth", assetWidth);
                            updateDigitalAsset(dctx, languageCode, FileName, FileType, uri, partNumber, assetType, representation, background, orientationView, assetHeight, assetWidth, userLogin);
                        } else {
                            Debug.logWarning("No DataResource found for fileName: " + FileName, MODULE);
                        }
                    }
                }
                if (ServiceUtil.isSuccess(updateResult)) {
                    Debug.logInfo("Product updated successfully: " + partNumber, MODULE);
                } else {
                    Debug.logError("Failed to update product: " + ServiceUtil.getErrorMessage(updateResult), MODULE);
                    return response;
                }
            } else {
                Debug.logInfo("Creating new product: " + partNumber, MODULE);
                Map<String, Object> createResult = dctx.getDispatcher().runSync("createProduct", productParams);
                if (ServiceUtil.isSuccess(createResult)) {
                    Debug.logInfo("Product created successfully: " + partNumber, MODULE);
                    createItemLevelGTIN(dctx, partNumber, itemLevelGTIN, userLogin);
                    createProductCategory(dctx, brandAAIAID, brandLabel, partTerminologyID, subBrandAAIAID, subBrandLabel, partNumber, userLogin);

                    //we are iterating through a list of descriptions; getOrDefault helps us prevent NUllPointerException
                    if(descriptions!=null) {
                        for (Map<String, String> desc : descriptions) {
                            String languageCode = desc.getOrDefault("LanguageCode", "");
                            String descriptionCode = desc.getOrDefault("DescriptionCode", "");
                            String finalText = desc.getOrDefault("Description", "");
                            createDescription(dctx, descriptionCode, languageCode, finalText, partNumber, userLogin);
                        }
                    }
                    if(extendedInformation!=null) {
                        for (Map<String, String> extInfo : extendedInformation) {
                            String expiCode = extInfo.getOrDefault("EXPICode", "");
                            String finalExtendedProductInformationText = extInfo.getOrDefault("ExtendedProductInformation", "");
                            storeExtendedProductInformation(dctx, expiCode, finalExtendedProductInformationText, partNumber, userLogin);
                        }
                    }
                    if(priceDetail!=null) {
                        for (Map<String, String> priceInfo : priceDetail) {
                            String priceType = priceInfo.getOrDefault("PriceType", "");
                            String currencyCode = priceInfo.getOrDefault("CurrencyCode", "");
                            String price = priceInfo.getOrDefault("Price", "");
                            createPrices(dctx, partNumber, priceType, price, userLogin);
                        }
                    }
                    if(digitalAssetDetail!=null) {
                        for (Map<String, String> digitalAssetInfo : digitalAssetDetail) {
                            String languageCode = digitalAssetInfo.getOrDefault("LanguageCode", "");
                            String FileName = digitalAssetInfo.getOrDefault("FileName", "");
                            String AssetType = digitalAssetInfo.getOrDefault("AssetType", "");
                            String representation = digitalAssetInfo.getOrDefault("Representation", "");
                            String background = digitalAssetInfo.getOrDefault("Background", "");
                            String orientationView = digitalAssetInfo.getOrDefault("OrientationView", "");
                            String assetHeight = digitalAssetInfo.getOrDefault("AssetHeight", "");
                            String assetWidth = digitalAssetInfo.getOrDefault("AssetWidth", "");
                            String uri = digitalAssetInfo.getOrDefault("URI", "");
                            String FileType = digitalAssetInfo.getOrDefault("FileType", "");
                            createDataResourceAttribute(dctx, AssetType, representation, background, orientationView, assetHeight, assetWidth, partNumber, userLogin);
                            createDigitalAssets(dctx, languageCode, FileName, FileType, uri, partNumber, userLogin);
                        }
                    }
                    if (partInterchangeDetail != null) {
                        for (Map<String, String> interchangeInfo : partInterchangeDetail) {
                            String partBrandAAIAID = interchangeInfo.getOrDefault("partBrandAAIAID", "");
                            String partBrandLabel = interchangeInfo.getOrDefault("partBrandLabel", "");
                            String itemEquivalentUOM = interchangeInfo.getOrDefault("ItemEquivalentUOM", "");
                            String partNumberTo = interchangeInfo.getOrDefault("PartNumberTo", "");
                            String interchangeQuantity = interchangeInfo.getOrDefault("InterchangeQuantity", "");
                            String productId = interchangeInfo.getOrDefault("productId", "");
                            createPartInterchangeInfo(dctx, partBrandAAIAID, partBrandLabel, interchangeQuantity, partNumberTo, itemEquivalentUOM,  productId, userLogin);
                        }
                    }
                    if(productAttributes!=null) {
                        for (Map<String, String> attrInfo : productAttributes) {
                            String attributeId = attrInfo.getOrDefault("AttributeID", "");
                            String finalProductAttributeText = attrInfo.getOrDefault("ProductAttribute", "");
                            storeProductAttribute(dctx, partNumber, attributeId, finalProductAttributeText, userLogin);
                        }
                    }
                    if (packageDetails != null) {
                        createPackages(dctx, packageDetails, partNumber, userLogin);
                    }
                } else {
                    Debug.logError("Failed to create product: " + ServiceUtil.getErrorMessage(createResult), MODULE);
                    return response;
                }
            }
        } catch (GenericServiceException | GenericEntityException e) {
            Debug.logError(e, "Error in createItem for partNumber: " + partNumber, MODULE);
            return response;
        }

        return response;
    }

    private static void createItemLevelGTIN(DispatchContext dctx, String partNumber, String itemLevelGTIN, GenericValue userLogin) {
        try {
            GenericValue existingGTIN = null;
            try {
                existingGTIN = EntityQuery.use(dctx.getDelegator())
                        .from("GoodIdentification")
                        .where("productId", partNumber, "goodIdentificationTypeId", "GTIN")
                        .queryFirst();
            } catch (GenericEntityException e) {
                Debug.logError("Error querying existing GTIN: " + e.getMessage(), MODULE);
                return;
            }

            if (existingGTIN != null) {
                Debug.logWarning("GTIN already exists for product: " + partNumber, MODULE);
                return;
            }
            GenericValue goodIdType = null;
            try {
                goodIdType = EntityQuery.use(dctx.getDelegator())
                        .from("GoodIdentificationType")
                        .where("goodIdentificationTypeId", "GTIN")
                        .queryOne();
            } catch (GenericEntityException e) {
                Debug.logError("Error querying GoodIdentificationType GTIN: " + e.getMessage(), MODULE);
                return;
            }
            if (goodIdType == null) {
                Map<String, Object> fields = UtilMisc.toMap(
                        "goodIdentificationTypeId", "GTIN",
                        "description", "Global Trade Item Number (GTIN)",
                        "userLogin", userLogin
                );
                Map<String, Object> result = dctx.getDispatcher().runSync("createGoodIdentificationType", fields);

                if (!ServiceUtil.isSuccess(result)) {
                    Debug.logError("Failed to create GoodIdentificationType GTIN: " + result.get("errorMessage"), MODULE);
                    return;
                }
            }
            Map<String, Object> goodIdentificationParams = UtilMisc.toMap(
                    "goodIdentificationTypeId", "GTIN",
                    "productId", partNumber,
                    "idValue", itemLevelGTIN,
                    "userLogin", userLogin
            );

            Map<String, Object> resultGoodIdentification = dctx.getDispatcher().runSync("createGoodIdentification", goodIdentificationParams);

            if (ServiceUtil.isSuccess(resultGoodIdentification)) {
                Debug.logInfo("GoodIdentification (GTIN) created for product: " + partNumber, MODULE);
            } else {
                Debug.logError("Error in createGoodIdentification: " + resultGoodIdentification.get("errorMessage"), MODULE);
            }

        } catch (GenericServiceException e) {
            Debug.logError("Exception in createItemLevelGTIN: " + e.getMessage(), MODULE);
        }
    }

    private static void updateItemLevelGTIN(DispatchContext dctx, String partNumber, String itemLevelGTIN, GenericValue userLogin) {
        try {
            GenericValue existingGTIN = null;
            try {
                existingGTIN = EntityQuery.use(dctx.getDelegator())
                        .from("GoodIdentification")
                        .where("productId", partNumber, "goodIdentificationTypeId", "GTIN")
                        .queryFirst();
            } catch (GenericEntityException e) {
                Debug.logError("Error querying existing GTIN: " + e.getMessage(), MODULE);
                return;
            }

            if (existingGTIN == null) {
                Debug.logWarning("No GTIN found for product: " + partNumber + ". Cannot update.", MODULE);
                return;
            }

            Map<String, Object> updateFields = UtilMisc.toMap(
                    "goodIdentificationTypeId", "GTIN",
                    "productId", partNumber,
                    "idValue", itemLevelGTIN,
                    "userLogin", userLogin
            );

            Map<String, Object> result = dctx.getDispatcher().runSync("updateGoodIdentification", updateFields);

            if (ServiceUtil.isSuccess(result)) {
                Debug.logInfo("GTIN updated for product: " + partNumber, MODULE);
            } else {
                Debug.logError("Failed to update GTIN for product: " + partNumber + ". Error: " + result.get("errorMessage"), MODULE);
            }

        } catch (GenericServiceException e) {
            Debug.logError("Exception in updateItemLevelGTIN: " + e.getMessage(), MODULE);
        }
    }

    public static void createProductCategory(DispatchContext dctx, String brandAAIAID, String brandLabel, String partTerminologyID, String subBrandAAIAID, String subBrandLabel, String partNumber, GenericValue userLogin) {
        Delegator delegator = dctx.getDelegator();

        String brandCategoryId = null;
        String subBrandCategoryId = null;
        String partTerminologyCategoryId = null;

        try {
            GenericValue brandCategoryType = EntityQuery.use(delegator)
                    .from("ProductCategoryType")
                    .where("productCategoryTypeId", "BRAND_CATEGORY")
                    .queryOne();

            if (UtilValidate.isEmpty(brandCategoryType)) {
                Map<String, Object> brandCategoryTypeParams = UtilMisc.toMap(
                        "productCategoryTypeId", "BRAND_CATEGORY",
                        "description", "Category for Brands",
                        "userLogin", userLogin
                );
                Map<String, Object> brandTypeResult = dctx.getDispatcher().runSync("createProductCategoryType", brandCategoryTypeParams);
                if (!ServiceUtil.isSuccess(brandTypeResult)) {
                    Debug.logError("Error creating BRAND_CATEGORY type: " + brandTypeResult.get("errorMessage"), MODULE);
                    return;
                }
            }

            GenericValue existingBrandCategory = EntityQuery.use(delegator)
                    .from("ProductCategory")
                    .where("categoryName", brandAAIAID, "productCategoryTypeId", "BRAND_CATEGORY")
                    .queryFirst();

            if (existingBrandCategory == null) {
                brandCategoryId = delegator.getNextSeqId("ProductCategory");

                Map<String, Object> brandCategoryParams = UtilMisc.toMap(
                        "productCategoryId", brandCategoryId,
                        "productCategoryTypeId", "BRAND_CATEGORY",
                        "categoryName", brandAAIAID,
                        "description", brandLabel,
                        "userLogin", userLogin
                );

                Map<String, Object> categoryResult = dctx.getDispatcher().runSync("createProductCategory", brandCategoryParams);
                if (ServiceUtil.isSuccess(categoryResult)) {
                    Debug.logInfo("BRAND_CATEGORY created with ID: " + brandCategoryId, MODULE);
                } else {
                    Debug.logError("Error creating BRAND_CATEGORY: " + categoryResult.get("errorMessage"), MODULE);
                    return;
                }
            } else {
                brandCategoryId = existingBrandCategory.getString("productCategoryId");
                Debug.logInfo("BRAND_CATEGORY already exists with ID: " + brandCategoryId, MODULE);
            }

            if (UtilValidate.isNotEmpty(subBrandAAIAID) && UtilValidate.isNotEmpty(subBrandLabel)) {
                GenericValue subBrandCategoryType = EntityQuery.use(delegator)
                        .from("ProductCategoryType")
                        .where("productCategoryTypeId", "SUBBRAND_CATEGORY")
                        .queryOne();

                if (UtilValidate.isEmpty(subBrandCategoryType)) {
                    Map<String, Object> subBrandCategoryTypeParams = UtilMisc.toMap(
                            "productCategoryTypeId", "SUBBRAND_CATEGORY",
                            "description", "Category for Sub Brands",
                            "userLogin", userLogin
                    );
                    dctx.getDispatcher().runSync("createProductCategoryType", subBrandCategoryTypeParams);
                }

                GenericValue existingSubBrandCategory = EntityQuery.use(delegator)
                        .from("ProductCategory")
                        .where("categoryName", subBrandAAIAID, "productCategoryTypeId", "SUBBRAND_CATEGORY")
                        .queryFirst();

                if (existingSubBrandCategory == null) {
                    subBrandCategoryId = delegator.getNextSeqId("ProductCategory");

                    Map<String, Object> subBrandCategoryParams = UtilMisc.toMap(
                            "productCategoryId", subBrandCategoryId,
                            "productCategoryTypeId", "SUBBRAND_CATEGORY",
                            "categoryName", subBrandAAIAID,
                            "description", subBrandLabel,
                            "userLogin", userLogin
                    );

                    Map<String, Object> subResult = dctx.getDispatcher().runSync("createProductCategory", subBrandCategoryParams);
                    if (ServiceUtil.isSuccess(subResult)) {
                        Debug.logInfo("SUBBRAND_CATEGORY created with ID: " + subBrandCategoryId, MODULE);
                        createProductCategoryRollup(dctx, brandCategoryId, subBrandCategoryId, userLogin);
                    } else {
                        Debug.logError("Error creating SUBBRAND_CATEGORY: " + subResult.get("errorMessage"), MODULE);
                    }
                } else {
                    subBrandCategoryId = existingSubBrandCategory.getString("productCategoryId");
                    Debug.logInfo("SUBBRAND_CATEGORY already exists with ID: " + subBrandCategoryId, MODULE);
                }
            }

            if (UtilValidate.isNotEmpty(partTerminologyID)) {
                GenericValue terminologyCategoryType = EntityQuery.use(delegator)
                        .from("ProductCategoryType")
                        .where("productCategoryTypeId", "PART_TERMINOLOGY")
                        .queryOne();

                if (UtilValidate.isEmpty(terminologyCategoryType)) {
                    Map<String, Object> terminologyTypeParams = UtilMisc.toMap(
                            "productCategoryTypeId", "PART_TERMINOLOGY",
                            "description", "Category for Part Terminology",
                            "userLogin", userLogin
                    );
                    Map<String, Object> terminologyResult = dctx.getDispatcher().runSync("createProductCategoryType", terminologyTypeParams);
                    if (!ServiceUtil.isSuccess(terminologyResult)) {
                        Debug.logError("Error creating PART_TERMINOLOGY type: " + terminologyResult.get("errorMessage"), MODULE);
                        return;
                    }
                }

                GenericValue existingTerminologyCategory = EntityQuery.use(delegator)
                        .from("ProductCategory")
                        .where("categoryName", partTerminologyID, "productCategoryTypeId", "PART_TERMINOLOGY")
                        .queryFirst();

                if (existingTerminologyCategory == null) {
                    partTerminologyCategoryId = delegator.getNextSeqId("ProductCategory");

                    Map<String, Object> partCategoryParams = UtilMisc.toMap(
                            "productCategoryId", partTerminologyCategoryId,
                            "productCategoryTypeId", "PART_TERMINOLOGY",
                            "categoryName", partTerminologyID,
                            "description", partTerminologyID,
                            "userLogin", userLogin
                    );

                    Map<String, Object> result = dctx.getDispatcher().runSync("createProductCategory", partCategoryParams);
                    if (ServiceUtil.isSuccess(result)) {
                        Debug.logInfo("PART_TERMINOLOGY Product Category created: " + partTerminologyCategoryId, MODULE);
                    } else {
                        Debug.logError("Error creating PART_TERMINOLOGY ProductCategory: " + result.get("errorMessage"), MODULE);
                    }
                } else {
                    partTerminologyCategoryId = existingTerminologyCategory.getString("productCategoryId");
                    Debug.logInfo("PART_TERMINOLOGY already exists for: " + partTerminologyID, MODULE);
                }
            }
            addProductToCategory(dctx, brandCategoryId, subBrandCategoryId, partTerminologyCategoryId, partNumber, userLogin);

        } catch (GenericServiceException e) {
            Debug.logError("ServiceException while creating product category: " + e.getMessage(), MODULE);
        } catch (Exception e) {
            Debug.logError("Unexpected error while creating product category: " + e.getMessage(), MODULE);
        }
    }

    //Helper method to link category with product
    private static void addProductToCategory(DispatchContext dctx, String brandCategoryId, String subBrandCategoryId, String partTerminologyCategoryId, String partNumber, GenericValue userLogin) {
        try {
            List<String> categoryIds = new ArrayList<>();
            if (UtilValidate.isNotEmpty(brandCategoryId)) categoryIds.add(brandCategoryId);
            if (UtilValidate.isNotEmpty(subBrandCategoryId)) categoryIds.add(subBrandCategoryId);
            if (UtilValidate.isNotEmpty(partTerminologyCategoryId)) categoryIds.add(partTerminologyCategoryId);

            for (String categoryId : categoryIds) {
                Map<String, Object> context = new HashMap<>();
                context.put("productCategoryId", categoryId);
                context.put("productId", partNumber);
                context.put("fromDate", UtilDateTime.nowTimestamp());
                context.put("userLogin", userLogin);

                Map<String, Object> result = dctx.getDispatcher().runSync("addProductToCategory", context);

                if (ServiceUtil.isSuccess(result)) {
                    Debug.logInfo("Product [" + partNumber + "] successfully added to category [" + categoryId + "]", MODULE);
                } else {
                    Debug.logError("Failed to add product to category [" + categoryId + "]: " + result.get("errorMessage"), MODULE);
                }
            }

        } catch (GenericServiceException e) {
            Debug.logError("Exception while adding product to categories: " + e.getMessage(), MODULE);
        }
    }

    public static void createProductCategoryRollup(DispatchContext dctx, String brandCategoryId, String subBrandCategoryId, GenericValue userLogin) {
        Delegator delegator = dctx.getDelegator();

        try {
            GenericValue existingRollup = EntityQuery.use(delegator)
                    .from("ProductCategoryRollup")
                    .where("productCategoryId", subBrandCategoryId, "parentProductCategoryId", brandCategoryId)
                    .queryFirst();

            if (existingRollup != null) {
                Debug.logInfo("ProductCategoryRollup already exists for: " + subBrandCategoryId + " -> " + brandCategoryId, MODULE);
                return;
            }
            GenericValue newRollup = delegator.makeValue("ProductCategoryRollup");
            newRollup.set("productCategoryId", subBrandCategoryId);
            newRollup.set("parentProductCategoryId", brandCategoryId);
            newRollup.set("fromDate", UtilDateTime.nowTimestamp());

            delegator.create(newRollup);
            Debug.logInfo("ProductCategoryRollup created successfully: " + subBrandCategoryId + " -> " + brandCategoryId, MODULE);

        } catch (GenericEntityException e) {
            Debug.logError(e, "Entity error while creating ProductCategoryRollup", MODULE);
        }
    }

    public static void updateProductCategory(DispatchContext dctx, String brandAAIAID, String brandLabel, String partTerminologyID, String subBrandAAIAID, String subBrandLabel, String partNumber, GenericValue userLogin) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();

        boolean brandUpdated = false;
        boolean subBrandUpdated = false;
        boolean partTerminologyUpdated = false;

        try {
            GenericValue existingBrandCategory = EntityQuery.use(delegator)
                    .from("ProductCategory")
                    .where("categoryName", brandAAIAID, "productCategoryTypeId", "BRAND_CATEGORY")
                    .queryFirst();

            if (existingBrandCategory != null) {
                String existingDescription = existingBrandCategory.getString("description");
                String categoryId = existingBrandCategory.getString("productCategoryId");
                if (!Objects.equals(existingDescription, brandLabel)) {
                    Map<String, Object> updateBrandCtx = UtilMisc.toMap(
                            "productCategoryId", categoryId,
                            "productCategoryTypeId", "BRAND_CATEGORY",
                            "description", brandLabel,
                            "userLogin", userLogin
                    );
                    Map<String, Object> updateResult = dispatcher.runSync("updateProductCategory", updateBrandCtx);
                    if (ServiceUtil.isSuccess(updateResult)) {
                        Debug.logInfo("Updated BRAND_CATEGORY with ID: " + categoryId+" and brandLabel: "+ brandLabel, MODULE);
                    } else {
                        Debug.logError("Failed to update BRAND_CATEGORY: " + updateResult.get("errorMessage"), MODULE);
                    }
                }
                brandUpdated = true;
            }
            else {
                createProductCategory(dctx, brandAAIAID, brandLabel, partTerminologyID, subBrandAAIAID, subBrandLabel, partNumber, userLogin);
            }
            if (UtilValidate.isNotEmpty(subBrandAAIAID) && UtilValidate.isNotEmpty(subBrandLabel)) {
                GenericValue existingSubBrandCategory = EntityQuery.use(delegator)
                        .from("ProductCategory")
                        .where("categoryName", subBrandAAIAID, "productCategoryTypeId", "SUBBRAND_CATEGORY")
                        .queryFirst();

                if (existingSubBrandCategory != null) {
                    String existingDescription = existingSubBrandCategory.getString("description");
                    String categoryId = existingSubBrandCategory.getString("productCategoryId");

                    if (!Objects.equals(existingDescription, subBrandLabel)) {
                        Map<String, Object> updateSubCtx = UtilMisc.toMap(
                                "productCategoryId", categoryId,
                                "productCategoryTypeId","SUBBRAND_CATEGORY",
                                "description", subBrandLabel,
                                "userLogin", userLogin
                        );
                        Map<String, Object> updateResult = dispatcher.runSync("updateProductCategory", updateSubCtx);
                        if (ServiceUtil.isSuccess(updateResult)) {
                            Debug.logInfo("Updated SUBBRAND_CATEGORY with ID: " + categoryId, MODULE);
                        } else {
                            Debug.logError("Failed to update SUBBRAND_CATEGORY: " + updateResult.get("errorMessage"), MODULE);
                        }
                    }
                    subBrandUpdated = true;
                }
                else {
                    createProductCategory(dctx, brandAAIAID, brandLabel, partTerminologyID, subBrandAAIAID, subBrandLabel, partNumber, userLogin);
                }
            }
            if (UtilValidate.isNotEmpty(partTerminologyID)) {
                GenericValue existingPartCategory = EntityQuery.use(delegator)
                        .from("ProductCategory")
                        .where("categoryName", partTerminologyID, "productCategoryTypeId", "PART_TERMINOLOGY")
                        .queryFirst();

                if (existingPartCategory != null) {
                    String existingDescription = existingPartCategory.getString("description");
                    String categoryId = existingPartCategory.getString("productCategoryId");

                    if (!Objects.equals(existingDescription, partTerminologyID)) {
                        Map<String, Object> updatePartCtx = UtilMisc.toMap(
                                "productCategoryId", categoryId,
                                "productCategoryTypeId", "PART_TERMINOLOGY",
                                "description", partTerminologyID,
                                "userLogin", userLogin
                        );
                        Map<String, Object> updateResult = dispatcher.runSync("updateProductCategory", updatePartCtx);
                        if (ServiceUtil.isSuccess(updateResult)) {
                            Debug.logInfo("Updated PART_TERMINOLOGY with ID: " + categoryId, MODULE);
                        } else {
                            Debug.logError("Failed to update PART_TERMINOLOGY: " + updateResult.get("errorMessage"), MODULE);
                        }
                    }
                    partTerminologyUpdated = true;
                }
                else {
                    createProductCategory(dctx, brandAAIAID, brandLabel, partTerminologyID, subBrandAAIAID, subBrandLabel, partNumber, userLogin);
                }
            }
        } catch (GenericServiceException e) {
            Debug.logError("Service exception in updateProductCategoryIfExistsOrCreate: " + e.getMessage(), MODULE);
        } catch (Exception e) {
            Debug.logError("Unexpected error in updateProductCategoryIfExistsOrCreate: " + e.getMessage(), MODULE);
        }
    }

//    private static void createDescription(DispatchContext dctx, String descriptionCode, String languageCode, String finalText, String partNumber, GenericValue userLogin) {
//        Delegator delegator = dctx.getDelegator();
//        String contentId = delegator.getNextSeqId("Content");
//
//        try {
//            if (UtilValidate.isNotEmpty(finalText) && finalText.length() > 255) {
//                Map<String, Object> electronicTextParams = UtilMisc.toMap(
//                        "textData", finalText,
//                        "userLogin", userLogin
//                );
//
//                Map<String, Object> electronicTextResult = dctx.getDispatcher().runSync("createElectronicText", electronicTextParams);
//
//                if (ServiceUtil.isSuccess(electronicTextResult)) {
//                    String dataResourceId = (String) electronicTextResult.get("dataResourceId");
//
//                    Map<String, Object> contentParams = UtilMisc.toMap(
//                            "contentId", contentId,
//                            "contentName", descriptionCode,
//                            "contentTypeId", "DOCUMENT",
//                            "localeString", languageCode,
//                            "description", null,
//                            "dataResourceId", dataResourceId,
//                            "userLogin", userLogin
//                    );
//
//                    Map<String, Object> contentResult = dctx.getDispatcher().runSync("createContent", contentParams);
//                    if (ServiceUtil.isSuccess(contentResult)) {
//                        Debug.logInfo("Content created successfully with contentId: " + contentId, MODULE);
//                    }
//                } else {
//                    Debug.logError("Error creating ElectronicText: " + electronicTextResult.get("errorMessage"), MODULE);
//                }
//            } else {
//                Map<String, Object> contentParams = UtilMisc.toMap(
//                        "contentId", contentId,
//                        "contentName", descriptionCode,
//                        "contentTypeId", "DOCUMENT",
//                        "localeString", languageCode,
//                        "description", finalText,
//                        "userLogin", userLogin
//                );
//                Map<String, Object> contentResult = dctx.getDispatcher().runSync("createContent", contentParams);
//                if (ServiceUtil.isSuccess(contentResult)) {
//                    Debug.logInfo("Content created successfully with contentId: " + contentId, MODULE);
//                }
//            }
//            Map<String, Object> productContentParams = UtilMisc.toMap(
//                    "productId", partNumber,
//                    "contentId", contentId,
//                    "productContentTypeId", "DESCRIPTION",
//                    "fromDate", UtilDateTime.nowTimestamp(),
//                    "userLogin", userLogin
//            );
//
//            Map<String, Object> productContentResult = dctx.getDispatcher().runSync("createProductContent", productContentParams);
//
//            if (!ServiceUtil.isSuccess(productContentResult)) {
//                Debug.logError("Error creating product content: " + productContentResult.get("errorMessage"), MODULE);
//                return;
//            }
//
//        } catch (GenericServiceException e) {
//            Debug.logError("Error creating description: " + e.getMessage(), MODULE);
//        }
//    }

    private static void createDescription(DispatchContext dctx, String descriptionCode, String languageCode, String finalText, String partNumber, GenericValue userLogin) {
        Delegator delegator = dctx.getDelegator();
        String contentId = delegator.getNextSeqId("Content");

        try {
            String dataResourceId = null;
            if (UtilValidate.isNotEmpty(finalText) && finalText.length() > 255) {
                Map<String, Object> electronicTextParams = UtilMisc.toMap(
                        "textData", finalText,
                        "userLogin", userLogin
                );

                Map<String, Object> electronicTextResult = dctx.getDispatcher().runSync("createElectronicText", electronicTextParams);
                if (!ServiceUtil.isSuccess(electronicTextResult)) {
                    Debug.logError("Error creating ElectronicText: " + ServiceUtil.getErrorMessage(electronicTextResult), MODULE);
                    return;
                }
                dataResourceId = (String) electronicTextResult.get("dataResourceId");
                Debug.logInfo("Created ElectronicText with dataResourceId: " + dataResourceId, MODULE);
            }

            Map<String, Object> contentParams = UtilMisc.toMap(
                    "contentId", contentId,
                    "contentName", descriptionCode,
                    "contentTypeId", "DOCUMENT",
                    "localeString", languageCode,
                    "description", finalText.length() <= 255 ? finalText : null,
                    "dataResourceId", dataResourceId,
                    "userLogin", userLogin
            );

            Map<String, Object> contentResult = dctx.getDispatcher().runSync("createContent", contentParams);
            if (!ServiceUtil.isSuccess(contentResult)) {
                Debug.logError("Error creating Content: " + ServiceUtil.getErrorMessage(contentResult), MODULE);
                return;
            }
            Debug.logInfo("Content created successfully with contentId: " + contentId, MODULE);

            Map<String, Object> productContentParams = UtilMisc.toMap(
                    "productId", partNumber,
                    "contentId", contentId,
                    "productContentTypeId", "DESCRIPTION",
                    "fromDate", UtilDateTime.nowTimestamp(),
                    "userLogin", userLogin
            );

            Map<String, Object> productContentResult = dctx.getDispatcher().runSync("createProductContent", productContentParams);
            if (!ServiceUtil.isSuccess(productContentResult)) {
                Debug.logError("Error creating ProductContent: " + ServiceUtil.getErrorMessage(productContentResult), MODULE);
                return;
            }
            Debug.logInfo("ProductContent created successfully for productId: " + partNumber + ", contentId: " + contentId, MODULE);

        } catch (GenericServiceException e) {
            Debug.logError("Error creating description: " + e.getMessage(), MODULE);
        }
    }

    private static void updateDescription(DispatchContext dctx, String descriptionCode, String languageCode, String finalText, String partNumber, GenericValue userLogin) throws GenericServiceException {
        Delegator delegator = dctx.getDelegator();
        try {
            GenericValue existingContent = EntityQuery.use(delegator)
                    .from("Content")
                    .where("contentName", descriptionCode)
                    .queryFirst();

            if (existingContent != null) {
                GenericValue existingProductContent = EntityQuery.use(delegator)
                        .from("ProductContent")
                        .where("productId", partNumber, "contentId", existingContent.getString("contentId"))
                        .queryFirst();

                if (existingProductContent != null) {
                    String existingDescription = existingContent.getString("description");

                    if (UtilValidate.isEmpty(finalText) || !finalText.equals(existingDescription)) {
                        if (finalText.length() > 255) {
                            GenericValue existingElectronicText = EntityQuery.use(delegator)
                                    .from("ElectronicText")
                                    .where("dataResourceId", existingContent.getString("dataResourceId"))
                                    .queryFirst();
                            Debug.logInfo("==== existingElectronicTExt ====" + existingElectronicText, MODULE);

                            if (existingElectronicText != null) {
                                existingElectronicText.set("textData", finalText);
                                existingElectronicText.store();

                                Debug.logInfo("ElectronicText updated successfully with new textData for ContentId: " + existingContent.getString("contentId"), MODULE);
                                Map<String, Object> updateElectronicTextParams = UtilMisc.toMap(
                                        "dataResourceId", existingElectronicText.getString("dataResourceId"),
                                        "textData", finalText,
                                        "userLogin", userLogin
                                );
                                Map<String, Object> updateElectronicTextResult = dctx.getDispatcher().runSync("updateElectronicText", updateElectronicTextParams);
                                if (ServiceUtil.isSuccess(updateElectronicTextResult)) {
                                    Debug.logInfo("ElectronicText service updated successfully for ContentId: " + existingContent.getString("contentId"), MODULE);
                                } else {
                                    Debug.logError("Failed to update ElectronicText service: " + updateElectronicTextResult.get("errorMessage"), MODULE);
                                }
                            } else {
                                Debug.logError("No matching ElectronicText found for ContentId: " + existingContent.getString("contentId"), MODULE);
                            }
                        } else {
                            existingContent.set("description", finalText);
                            existingContent.store();

                            Debug.logInfo("Content updated successfully with new description for ContentId: " + existingContent.getString("contentId"), MODULE);
                            Map<String, Object> updateContentParams = UtilMisc.toMap(
                                    "contentId", existingContent.getString("contentId"),
                                    "description", finalText,
                                    "userLogin", userLogin
                            );
                            Map<String, Object> updateContentResult = dctx.getDispatcher().runSync("updateContent", updateContentParams);
                            if (ServiceUtil.isSuccess(updateContentResult)) {
                                Debug.logInfo("Content service updated successfully for ContentId: " + existingContent.getString("contentId"), MODULE);
                            } else {
                                Debug.logError("Failed to update Content service: " + updateContentResult.get("errorMessage"), MODULE);
                            }
                        }
                    } else {
                        Debug.logInfo("Description is the same as the existing one. No update needed for PartNumber: " + partNumber + " and DescriptionCode: " + descriptionCode, MODULE);
                    }
                } else {
                    Debug.logError("No matching ProductContent record found for PartNumber: " + partNumber + " and DescriptionCode: " + descriptionCode, MODULE);
                }
            } else {
                Debug.logError("No matching Content record found for DescriptionCode: " + descriptionCode + " and LanguageCode: " + languageCode, MODULE);
            }
        } catch (GenericEntityException e) {
            Debug.logError("Error updating description: " + e.getMessage(), MODULE);
        }
    }

    public static void storeExtendedProductInformation(DispatchContext dctx, String expiCode, String finalExtendedProductInformationText, String partNumber, GenericValue userLogin) {

        Delegator delegator = dctx.getDelegator();
        String productFeatureId = delegator.getNextSeqId("ProductFeature");

        try {
            GenericValue productFeatureType = EntityQuery.use(delegator)
                    .from("ProductFeatureType")
                    .where("productFeatureTypeId", "EXPI")
                    .queryOne();

            if (UtilValidate.isEmpty(productFeatureType)) {
                Map<String, Object> productFeatureTypeParams = UtilMisc.toMap(
                        "productFeatureTypeId", "EXPI",
                        "userLogin", userLogin
                );
                Map<String, Object> featureTypeResult = dctx.getDispatcher().runSync("createProductFeatureType", productFeatureTypeParams);

                if (!ServiceUtil.isSuccess(featureTypeResult)) {
                    Debug.logError("Error creating ProductFeatureType: " + featureTypeResult.get("errorMessage"), MODULE);
                    return;
                }
            }
            GenericValue existingProductFeature = EntityQuery.use(delegator)
                    .from("ProductFeature")
                    .where("productFeatureId", productFeatureId)
                    .queryOne();

            if (existingProductFeature != null) {
                Debug.logWarning("Product with productFeatureId: " + productFeatureId + " already exists.", MODULE);
                return;
            }
            Map<String, Object> productFeatureParams = UtilMisc.toMap(
                    "productFeatureId", productFeatureId,
                    "productFeatureTypeId", "EXPI",
                    "idCode", expiCode,
                    "description", finalExtendedProductInformationText,
                    "userLogin", userLogin
            );

            Map<String, Object> featureResult = dctx.getDispatcher().runSync("createProductFeature", productFeatureParams);

            if (ServiceUtil.isSuccess(featureResult)) {
                Debug.logInfo("Product Feature created successfully with productFeatureId: " + productFeatureId, MODULE);
                applyFeatureToProduct(dctx, partNumber, productFeatureId, userLogin);
            } else {
                Debug.logError("Error creating ProductFeature: " + featureResult.get("errorMessage"), MODULE);
            }

        } catch (GenericServiceException e) {
            Debug.logError("ServiceException while creating product feature: " + e.getMessage(), MODULE);
        } catch (Exception e) {
            Debug.logError("Unexpected error while creating product feature: " + e.getMessage(), MODULE);
        }
    }

    //helper method to link product with feature
    public static void applyFeatureToProduct(DispatchContext dctx, String partNumber, String productFeatureId, GenericValue userLogin) {
        try {
            Map<String, Object> context = new HashMap<>();
            context.put("productId", partNumber);
            context.put("productFeatureId", productFeatureId);
            context.put("productFeatureApplTypeId", "STANDARD_FEATURE");
            context.put("fromDate", UtilDateTime.nowTimestamp());
            context.put("userLogin", userLogin);

            Map<String, Object> result = dctx.getDispatcher().runSync("applyFeatureToProduct", context);

            if (ServiceUtil.isSuccess(result)) {
                Debug.logInfo("Successfully applied feature [" + productFeatureId + "] to product [" + partNumber + "]", MODULE);
            } else {
                Debug.logError("Error applying feature to product: " + result.get("errorMessage"), MODULE);
            }
        } catch (GenericServiceException e) {
            Debug.logError("Exception during applyFeatureToProduct: " + e.getMessage(), MODULE);
        }
    }

    public static void updateExtendedProductInformation(DispatchContext dctx, String expiCode, String finalExtendedProductInformationText, String partNumber, GenericValue userLogin) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();

        try {
            GenericValue productFeature = EntityQuery.use(delegator)
                    .from("ProductFeature")
                    .where("productFeatureTypeId", "EXPI", "idCode", expiCode)
                    .queryFirst();

            if (productFeature != null) {
                String productFeatureId = productFeature.getString("productFeatureId");

                GenericValue featureAppl = EntityQuery.use(delegator)
                        .from("ProductFeatureAppl")
                        .where("productId", partNumber, "productFeatureId", productFeatureId)
                        .queryFirst();

                if (featureAppl != null) {
                    String existingDescription = productFeature.getString("description");

                    if (!Objects.equals(existingDescription, finalExtendedProductInformationText)) {
                        Map<String, Object> updateParams = UtilMisc.toMap(
                                "productFeatureId", productFeatureId,
                                "productFeatureTypeId", "EXPI",
                                "description", finalExtendedProductInformationText,
                                "userLogin", userLogin
                        );

                        Map<String, Object> updateResult = dispatcher.runSync("updateProductFeature", updateParams);

                        if (ServiceUtil.isSuccess(updateResult)) {
                            Debug.logInfo("Updated ProductFeature for productFeatureId: " + productFeatureId + ", idCode: " + expiCode, MODULE);
                        } else {
                            Debug.logError("Failed to update ProductFeature: " + updateResult.get("errorMessage"), MODULE);
                        }
                    } else {
                        Debug.logInfo("ProductFeature already has the same description: " + productFeatureId, MODULE);
                    }
                } else {
                    Debug.logWarning("ProductFeature found but not applied to productId: " + partNumber, MODULE);
                }
            } else {
                Debug.logWarning("No ProductFeature found for idCode: " + expiCode + " and type: EXPI", MODULE);
            }

        } catch (GenericServiceException | GenericEntityException e) {
            Debug.logError("Exception in updateExtendedProductInformation: " + e.getMessage(), MODULE);
        } catch (Exception e) {
            Debug.logError("Unexpected error in updateExtendedProductInformation: " + e.getMessage(), MODULE);
        }
    }

    private static void storeProductAttribute(DispatchContext dctx, String partNumber, String attributeId, String finalProductAttributeText, GenericValue userLogin) {

        try {
            GenericValue existingProductAttribute = EntityQuery.use(dctx.getDelegator())
                    .from("ProductAttribute")
                    .where("productId", partNumber, "attrName", attributeId)
                    .queryOne();

            if (existingProductAttribute != null) {
                Debug.logWarning("Product Attribute already exists", MODULE);
            } else {
                Map<String, Object> productAttributeParams = UtilMisc.toMap(
                        "productId", partNumber,
                        "attrName", attributeId,
                        "attrValue", finalProductAttributeText,
                        "attrType", "PADB_ATTRIBUTE",
                        "userLogin", userLogin
                );

                Map<String, Object> productAttributeresult = dctx.getDispatcher().runSync("createProductAttribute", productAttributeParams);
                Debug.logInfo("Product Attribute created successfully", MODULE);
            }
        } catch (Exception e) {
            Debug.logError("Error creating product attribute: " + e.getMessage(), MODULE);
        }
    }

    public static void updateProductAttribute(DispatchContext dctx, String partNumber, String attributeId, String finalProductAttributeText, GenericValue userLogin) {
        Delegator delegator = dctx.getDelegator();

        try {
            GenericValue existingProductAttribute = EntityQuery.use(delegator)
                    .from("ProductAttribute")
                    .where("productId", partNumber, "attrName", attributeId)
                    .queryFirst();

            if (existingProductAttribute != null) {
                String existingAttrValue = existingProductAttribute.getString("attrValue");

                if (!Objects.equals(existingAttrValue, finalProductAttributeText)) {
                    Map<String, Object> updateParams = UtilMisc.toMap(
                            "productId", partNumber,
                            "attrName", attributeId,
                            "attrValue", finalProductAttributeText,
                            "attrType", "PADB_ATTRIBUTE",
                            "userLogin", userLogin
                    );
                    Map<String, Object> updateResult = dctx.getDispatcher().runSync("updateProductAttribute", updateParams);

                    if (ServiceUtil.isSuccess(updateResult)) {
                        Debug.logInfo("Updated ProductAttribute for productId: " + partNumber + ", attribute: " + attributeId, MODULE);
                    } else {
                        Debug.logError("Failed to update ProductAttribute: " + updateResult.get("errorMessage"), MODULE);
                    }
                } else {
                    Debug.logInfo("ProductAttribute already exists with the same value for productId: " + partNumber + ", attribute: " + attributeId, MODULE);
                }
            } else {
                Debug.logWarning("ProductAttribute does not exist for productId: " + partNumber + ", attribute: " + attributeId, MODULE);
            }

        } catch (GenericServiceException e) {
            Debug.logError("ServiceException in updateProductAttribute: " + e.getMessage(), MODULE);
        } catch (Exception e) {
            Debug.logError("Unexpected error in updateProductAttribute: " + e.getMessage(), MODULE);
        }
    }

    public static void createPackages(DispatchContext dctx, List<Map<String, String>> packages, String partNumber, GenericValue userLogin) {
        Delegator delegator = dctx.getDelegator();

        try {
            GenericValue productFeatureCategory = EntityQuery.use(delegator)
                    .from("ProductFeatureCategory")
                    .where("productFeatureCategoryId", "PACKAGE")
                    .queryOne();
            if (productFeatureCategory == null) {
                Map<String, Object> catCtx = UtilMisc.toMap(
                        "productFeatureCategoryId", "PACKAGE",
                        "description", "This will contain information about package",
                        "userLogin", userLogin
                );
                dctx.getDispatcher().runSync("createProductFeatureCategory", catCtx);
                Debug.logInfo("ProductFeatureCategory 'PACKAGE' created successfully!", MODULE);
            }
            for (Map<String, String> pkg : packages) { //loops through each package map in the list
                String packageUOM = pkg.get("PackageUOM");
                for (Map.Entry<String, String> entry : pkg.entrySet()) { //loops through each key value pair in the pkg

                    GenericValue Uom = EntityQuery.use(delegator)
                            .from("Uom")
                            .where("uomId", packageUOM)
                            .queryOne();
                    if (Uom == null) {
                        Map<String, Object> uomCtx = UtilMisc.toMap(
                                "uomId", packageUOM,
                                "uomTypeId", "OTHER_MEASURE",
                                "abbreviation", packageUOM,
                                "userLogin", userLogin
                        );
                        dctx.getDispatcher().runSync("createUom", uomCtx);
                        Debug.logInfo("Uom created successfully!", MODULE);
                    }else {
                        Debug.logInfo("Uom already exists: " + packageUOM, MODULE);
                    }

                    //then accessing these key value pairs
                    String tagName = entry.getKey();
                    String tagValue = entry.getValue();

                    if (UtilValidate.isEmpty(tagValue))
                        continue;

                    String productFeatureId = delegator.getNextSeqId("ProductFeature");
                    String productFeatureTypeId;

                    if ("Weight".equalsIgnoreCase(tagName)) {
                        productFeatureTypeId = "NET_WEIGHT";
                    } else if (tagName.startsWith("Merchandising") || tagName.startsWith("Shipping") || "DimensionsUOM".equals(tagName)) {
                        productFeatureTypeId = "DIMENSION";
                    } else {
                        productFeatureTypeId = "OTHER_FEATURE";
                    }

                    //for internally nested elements uom id is different so have to handle that case as well
                    Map<String, Object> featureCtx = new HashMap<>();
                    featureCtx.put("productFeatureId", productFeatureId);
                    featureCtx.put("productFeatureTypeId", productFeatureTypeId);
                    featureCtx.put("productFeatureCategoryId", "PACKAGE");
                    featureCtx.put("description", tagName + ": " + tagValue);
                    featureCtx.put("uomId", packageUOM);
                    featureCtx.put("userLogin", userLogin);
                    Debug.logInfo("Creating ProductFeature with context: " + featureCtx, MODULE);

                    Map<String, Object> result = dctx.getDispatcher().runSync("createProductFeature", featureCtx);
                    if (ServiceUtil.isSuccess(result)) {
                        Debug.logInfo("Created ProductFeature: " + tagName + " = " + tagValue, MODULE);
                        applyFeatureToProduct(dctx, partNumber, productFeatureId, userLogin);
                    } else {
                        Debug.logError("Failed to create ProductFeature for " + tagName + ": " + result.get("errorMessage"), MODULE);
                    }
                }
            }
        } catch (Exception e) {
            Debug.logError("Exception while creating package features: " + e.getMessage(), MODULE);
        }
    }

    public static void updatePackages(DispatchContext dctx, List<Map<String, String>> packages, String partNumber, GenericValue userLogin) {
        Delegator delegator = dctx.getDelegator();

        try {
            GenericValue productFeatureCategory = EntityQuery.use(delegator)
                    .from("ProductFeatureCategory")
                    .where("productFeatureCategoryId", "PACKAGE")
                    .queryOne();

            if (productFeatureCategory == null) {
                Debug.logWarning("ProductFeatureCategory 'PACKAGE' does not exist. Skipping update.", MODULE);
                return;
            }

            for (Map<String, String> pkg : packages) {
                String packageUOM = pkg.get("PackageUOM");
                if (UtilValidate.isEmpty(packageUOM)) {
                    Debug.logWarning("Missing PackageUOM in package map, skipping.", MODULE);
                    continue;
                }

                for (Map.Entry<String, String> entry : pkg.entrySet()) {
                    String tagName = entry.getKey();
                    String tagValue = entry.getValue();

                    if ("PackageUOM".equals(tagName) || UtilValidate.isEmpty(tagValue)) {
                        continue;
                    }

                    String productFeatureTypeId;
                    if ("Weight".equalsIgnoreCase(tagName)) {
                        productFeatureTypeId = "NET_WEIGHT";
                    } else if (tagName.startsWith("Merchandising") || tagName.startsWith("Shipping") || "DimensionsUOM".equals(tagName)) {
                        productFeatureTypeId = "DIMENSION";
                    } else {
                        productFeatureTypeId = "OTHER_FEATURE";
                    }

                    String expectedDescriptionPrefix = tagName + ":";

                    List<GenericValue> matchingFeatures = EntityQuery.use(delegator)
                            .from("ProductFeature")
                            .where("productFeatureCategoryId", "PACKAGE", "uomId", packageUOM)
                            .queryList();

                    GenericValue existingFeature = null;

                    for (GenericValue feature : matchingFeatures) {
                        String desc = feature.getString("description");
                        if (desc != null && desc.startsWith(expectedDescriptionPrefix)) {
                            existingFeature = feature;
                            break;
                        }
                    }

                    if (existingFeature != null) {
                        String productFeatureId = existingFeature.getString("productFeatureId");
                        String existingDescription = existingFeature.getString("description");

                        String newDescription = tagName + ": " + tagValue;

                        if (!Objects.equals(existingDescription, newDescription)) {
                            Map<String, Object> updateCtx = new HashMap<>();
                            updateCtx.put("productFeatureId", productFeatureId);
                            updateCtx.put("productFeatureTypeId", productFeatureTypeId);
                            updateCtx.put("productFeatureCategoryId", "PACKAGE");
                            updateCtx.put("description", newDescription);
                            updateCtx.put("uomId", packageUOM);
                            updateCtx.put("userLogin", userLogin);

                            Map<String, Object> result = dctx.getDispatcher().runSync("updateProductFeature", updateCtx);
                            if (ServiceUtil.isSuccess(result)) {
                                Debug.logInfo("Updated ProductFeature: " + tagName + " = " + tagValue + ", ID: " + productFeatureId, MODULE);
                            } else {
                                Debug.logError("Failed to update ProductFeature for " + tagName + ": " + result.get("errorMessage"), MODULE);
                            }
                        } else {
                            Debug.logInfo("No update needed for ProductFeature: " + tagName, MODULE);
                        }

                        GenericValue appl = EntityQuery.use(delegator)
                                .from("ProductFeatureAppl")
                                .where("productId", partNumber, "productFeatureId", productFeatureId)
                                .queryFirst();

                        if (appl == null) {
                            applyFeatureToProduct(dctx, partNumber, productFeatureId, userLogin);
                            Debug.logInfo("Applied ProductFeature to product: " + partNumber, MODULE);
                        }

                    } else {
                        Debug.logWarning("ProductFeature not found for tag: " + tagName + " with category 'PACKAGE' and UOM: " + packageUOM, MODULE);
                    }
                }
            }
        } catch (Exception e) {
            Debug.logError("Exception while updating package features: " + e.getMessage(), MODULE);
        }
    }

    private static void createPrices(DispatchContext dctx, String partNumber, String priceType, String price, GenericValue userLogin) {
        Delegator delegator = dctx.getDelegator();
        try {
            GenericValue productPriceType = EntityQuery.use(delegator)
                    .from("ProductPriceType")
                    .where("productPriceTypeId", priceType)
                    .queryOne();

            if (UtilValidate.isEmpty(productPriceType)) {
                Map<String, Object> productPriceTypeParams = UtilMisc.toMap(
                        "productPriceTypeId", priceType,
                        "userLogin", userLogin
                );

                Map<String, Object> priceTypeResult = dctx.getDispatcher().runSync("createProductPriceType", productPriceTypeParams);

                if (!ServiceUtil.isSuccess(priceTypeResult)) {
                    Debug.logError("Error creating ProductPriceType: " + priceTypeResult.get("errorMessage"), MODULE);
                    return;
                }
            }

            Map<String, Object> productPriceParams = UtilMisc.toMap(
                    "productId", partNumber,
                    "productPriceTypeId", priceType,
                    "productPricePurposeId", "PURCHASE",
                    "currencyUomId", "USD",
                    "productStoreGroupId", "_NA_",
                    "termUomID", "PE",
                    "fromDate", UtilDateTime.nowTimestamp(),
                    "price", price,
                    "userLogin", userLogin
            );

            Map<String, Object> priceResult = dctx.getDispatcher().runSync("createProductPrice", productPriceParams);

            if (!ServiceUtil.isSuccess(priceResult)) {
                Debug.logError("Error creating ProductPrice: " + priceResult.get("errorMessage"), MODULE);
                return;
            }

            Debug.logInfo("Product Price created successfully for PartNumber: " + partNumber, MODULE);
        } catch (Exception e) {
            Debug.logError(e, "Error creating ProductPrice", MODULE);
        }
    }

    private static void updatePrices(DispatchContext dctx, String partNumber, String priceType, String price, GenericValue userLogin) {
        Delegator delegator = dctx.getDelegator();
        try {
            GenericValue existingPrice = EntityQuery.use(delegator)
                    .from("ProductPrice")
                    .where("productId", partNumber, "productPriceTypeId", priceType)
                    .queryFirst();

            Timestamp now = UtilDateTime.nowTimestamp();

            if (existingPrice != null) {
                BigDecimal existingPriceValue = existingPrice.getBigDecimal("price");
                BigDecimal newPriceValue = new BigDecimal(price);

                if (existingPriceValue.compareTo(newPriceValue) == 0) {
                    Debug.logInfo("Price already exists with the same value for ProductId: " + partNumber, MODULE);
                    return;
                } else {
                    existingPrice.set("thruDate", now);
                    existingPrice.store();
                    Debug.logInfo("Updated thruDate for existing price for PartNumber: " + partNumber, MODULE);
                }
            } else {
                Debug.logInfo("No existing price found for PartNumber: " + partNumber + " and PriceType: " + priceType, MODULE);
            }
            createPrices(dctx, partNumber, priceType, price, userLogin);
            Debug.logInfo("New Product Price created successfully for PartNumber: " + partNumber, MODULE);

        } catch (GenericEntityException e) {
            Debug.logError(e, "Exception occurred in updatePrices for PartNumber: " + partNumber, MODULE);
        }
    }

    private static void createDigitalAssets(DispatchContext dctx, String languageCode, String fileName, String fileType, String uri, String partNumber, GenericValue userLogin) {

        Delegator delegator = dctx.getDelegator();
        String dataResourceId = delegator.getNextSeqId("DataResource");

        try {
            Map<String, Object> dataResourceParams = UtilMisc.toMap(
                    "dataResourceId", dataResourceId,
                    "dataResourceTypeId", "IMAGE_OBJECT",
                    "dataResourceName", fileName,
                    "localeString", languageCode,
                    "mimeTypeId", fileType,
                    "objectInfo", uri,
                    "userLogin", userLogin
            );

            Map<String, Object> dataResourceResult = dctx.getDispatcher().runSync("createDataResource", dataResourceParams);

            if (!ServiceUtil.isSuccess(dataResourceResult)) {
                Debug.logError("Error creating data resource: " + dataResourceResult.get("errorMessage"), MODULE);
                return;
            }

            Map<String, Object> contentParams = UtilMisc.toMap(
                    "dataResourceId", dataResourceId,
                    "contentTypeId", "DOCUMENT",
                    "userLogin", userLogin
            );

            Map<String, Object> contentResult = dctx.getDispatcher().runSync("createContent", contentParams);

            if (!ServiceUtil.isSuccess(contentResult)) {
                Debug.logError("Error creating content: " + contentResult.get("errorMessage"), MODULE);
                return;
            }

            //gets the auto-generated contentId
            String contentId = (String) contentResult.get("contentId");
            Map<String, Object> productContentParams = UtilMisc.toMap(
                    "productId", partNumber,
                    "contentId", contentId,
                    "productContentTypeId", "DIGITAL_DOWNLOAD",
                    "fromDate", UtilDateTime.nowTimestamp(),
                    "userLogin", userLogin
            );

            Map<String, Object> productContentResult = dctx.getDispatcher().runSync("createProductContent", productContentParams);

            if (!ServiceUtil.isSuccess(productContentResult)) {
                Debug.logError("Error creating product content: " + productContentResult.get("errorMessage"), MODULE);
                return;
            }

        } catch (GenericServiceException e) {
            Debug.logError(e, "Error creating Data Resource", MODULE);
        }
    }

    private static void createDataResourceAttribute(DispatchContext dctx, String assetType, String representation, String background, String orientationView, String assetHeight, String assetWidth, String partNumber, GenericValue userLogin) {

        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        String dataResourceId = delegator.getNextSeqId("DataResource");

        try {
            Map<String, Object> createDataResourceCtx = UtilMisc.toMap(
                    "dataResourceId", dataResourceId,
                    "dataResourceTypeId", "IMAGE_OBJECT",
                    "statusId", "CTNT_PUBLISHED",
                    "dataResourceName", "AutoCreatedDataResource",
                    "userLogin", userLogin
            );
            Map<String, Object> dataResourceResult = dispatcher.runSync("createDataResource", createDataResourceCtx);
            if (ServiceUtil.isError(dataResourceResult)) {
                Debug.logError("Error creating DataResource: " + dataResourceResult.get("errorMessage"), MODULE);
                return;
            }
            Debug.logInfo("Successfully created DataResource with ID: " + dataResourceId, MODULE);
            Map<String, String> attributes = new HashMap<>();
            attributes.put("AssetType", assetType);
            attributes.put("Representation", representation);
            attributes.put("Background", background);
            attributes.put("OrientationView", orientationView);
            attributes.put("AssetHeight", assetHeight);
            attributes.put("AssetWidth", assetWidth);
            for (Map.Entry<String, String> entry : attributes.entrySet()) {
                String tagName = entry.getKey();
                String tagValue = entry.getValue();
                if (UtilValidate.isEmpty(tagValue)) continue;
                GenericValue existingAttr = EntityQuery.use(delegator)
                        .from("DataResourceAttribute")
                        .where("dataResourceId", dataResourceId, "attrName", tagName)
                        .queryOne();
                if (UtilValidate.isEmpty(existingAttr)) {
                    Map<String, Object> params = UtilMisc.toMap(
                            "dataResourceId", dataResourceId,
                            "attrName", tagName,
                            "attrValue", tagValue,
                            "userLogin", userLogin
                    );
                    Map<String, Object> result = dispatcher.runSync("createDataResourceAttribute", params);
                    if (ServiceUtil.isSuccess(result)) {
                        Debug.logInfo("Created DataResourceAttribute: " + tagName + " = " + tagValue, MODULE);
                        createContentAndProductContent(dctx, dataResourceId, partNumber, userLogin);
                    } else {
                        Debug.logError("Error creating attribute for " + tagName + ": " + result.get("errorMessage"), MODULE);
                    }
                } else {
                    Debug.logInfo("Attribute already exists for: " + tagName + ", skipping creation.", MODULE);
                }
            }

        } catch (GenericServiceException | GenericEntityException e) {
            Debug.logError(e, "Error while creating DataResourceAttributes", MODULE);
        } catch (Exception e) {
            Debug.logError(e, "Unexpected error while creating DataResourceAttributes", MODULE);
        }
    }

    //Helper method to create content and product Content
    private static void createContentAndProductContent(DispatchContext dctx, String dataResourceId, String partNumber, GenericValue userLogin) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();

        try {
            String contentId = delegator.getNextSeqId("Content");
            Map<String, Object> contentCtx = UtilMisc.toMap(
                    "contentId", contentId,
                    "dataResourceId", dataResourceId,
                    "contentTypeId", "DOCUMENT",
                    "userLogin", userLogin
            );
            Map<String, Object> contentResult = dispatcher.runSync("createContent", contentCtx);

            if (ServiceUtil.isError(contentResult)) {
                Debug.logError("Error creating Content: " + contentResult.get("errorMessage"), MODULE);
                return;
            }
            Debug.logInfo("Successfully created Content with ID: " + contentId, MODULE);

            Map<String, Object> productContentCtx = UtilMisc.toMap(
                    "productId", partNumber,
                    "contentId", contentId,
                    "productContentTypeId", "DIGITAL_DOWNLOAD",
                    "fromDate", UtilDateTime.nowTimestamp(),
                    "userLogin", userLogin
            );
            Map<String, Object> productContentResult = dispatcher.runSync("createProductContent", productContentCtx);

            if (ServiceUtil.isSuccess(productContentResult)) {
                Debug.logInfo("Successfully created ProductContent for product: " + partNumber + ", content: " + contentId, MODULE);
            } else {
                Debug.logError("Error creating ProductContent: " + productContentResult.get("errorMessage"), MODULE);
            }

        } catch (GenericServiceException e) {
            Debug.logError(e, "ServiceException while creating Content and ProductContent", MODULE);
        } catch (Exception e) {
            Debug.logError(e, "Unexpected error while creating Content and ProductContent", MODULE);
        }
    }

    private static void updateDigitalAsset(DispatchContext dctx, String languageCode, String fileName, String fileType, String uri, String partNumber, String assetType, String representation, String background, String orientationView, String assetHeight, String assetWidth, GenericValue userLogin) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();

        try {
            GenericValue existingDataResource = EntityQuery.use(delegator)
                    .from("DataResource")
                    .where("dataResourceName", fileName)
                    .queryFirst();

            if (existingDataResource == null) {
                Debug.logError("DataResource not found for name: " + fileName, MODULE);
                return;
            }
            String dataResourceId = existingDataResource.getString("dataResourceId");
            Map<String, Object> updateAssetCtx = UtilMisc.toMap(
                    "dataResourceId", dataResourceId,
                    "fileName", fileName,
                    "mimeTypeId", fileType,
                    "objectInfo", uri,
                    "localeString", languageCode,
                    "userLogin", userLogin
            );

            Map<String, Object> result = dispatcher.runSync("updateDataResource", updateAssetCtx);

            if (ServiceUtil.isSuccess(result)) {
                Debug.logInfo("Successfully updated digital asset with ID: " + dataResourceId, MODULE);
            } else {
                Debug.logError("Failed to update digital asset: " + result.get("errorMessage"), MODULE);
                return;
            }
            Map<String, String> attributes = new HashMap<>();
            attributes.put("AssetType", assetType);
            attributes.put("Representation", representation);
            attributes.put("Background", background);
            attributes.put("OrientationView", orientationView);
            attributes.put("AssetHeight", assetHeight);
            attributes.put("AssetWidth", assetWidth);

            for (Map.Entry<String, String> entry : attributes.entrySet()) {
                String attrName = entry.getKey();
                String attrValue = entry.getValue();
                if (UtilValidate.isEmpty(attrValue)) continue;

                GenericValue existingAttr = EntityQuery.use(delegator)
                        .from("DataResourceAttribute")
                        .where("dataResourceId", dataResourceId, "attrName", attrName)
                        .queryFirst();

                if (existingAttr == null) {
                    createDataResourceAttribute(dctx, assetType, representation, background, orientationView, assetHeight, assetWidth, partNumber, userLogin);
                    break;
                } else if (!attrValue.equals(existingAttr.getString("attrValue"))) {
                    existingAttr.set("attrValue", attrValue);
                    existingAttr.store();
                    Debug.logInfo("Updated DataResourceAttribute: " + attrName + " to " + attrValue, MODULE);
                }
            }

        } catch (GenericEntityException | GenericServiceException e) {
            Debug.logError(e, "Error while updating digital asset details", MODULE);
        }
    }

    private static void createPartInterchangeInfo(DispatchContext dctx, String partBrandAAIAID, String partBrandLabel, String interchangeQuantity, String partNumberTo, String itemEquivalentUOM,  String productId, GenericValue userLogin) {
        Delegator delegator = dctx.getDelegator();

        try {

            GenericValue productCategoryType = EntityQuery.use(delegator)
                    .from("ProductCategoryType")
                    .where("productCategoryTypeId", "BRAND_CATEGORY")
                    .queryOne();

            if (UtilValidate.isEmpty(productCategoryType)) {
                Map<String, Object> productCategoryTypeParams = UtilMisc.toMap(
                        "productCategoryTypeId", "BRAND_CATEGORY",
                        "userLogin", userLogin
                );

                Map<String, Object> categoryTypeResult = dctx.getDispatcher().runSync("createProductCategoryType", productCategoryTypeParams);

                if (!ServiceUtil.isSuccess(categoryTypeResult)) {
                    Debug.logError("Error creating ProductCategoryType: " + categoryTypeResult.get("errorMessage"), MODULE);
                    return;
                }
            }

            GenericValue existingProductCategory = EntityQuery.use(delegator)
                    .from("ProductCategory")
                    .where("categoryName", partBrandAAIAID)
                    .queryFirst();

            String productCategoryId;
            if (existingProductCategory != null) {
                productCategoryId = existingProductCategory.getString("productCategoryId");
                Debug.logInfo("Product Category already exists with ID: " + productCategoryId, MODULE);
            } else {
                productCategoryId = delegator.getNextSeqId("ProductCategory");

                Map<String, Object> productCategoryParams = UtilMisc.toMap(
                        "productCategoryId", productCategoryId,
                        "productCategoryTypeId", "BRAND_CATEGORY",
                        "categoryName", partBrandAAIAID,
                        "description", partBrandLabel,
                        "userLogin", userLogin
                );

                Map<String, Object> categoryResult = dctx.getDispatcher().runSync("createProductCategory", productCategoryParams);

                if (!ServiceUtil.isSuccess(categoryResult)) {
                    Debug.logError("Error creating ProductCategory: " + categoryResult.get("errorMessage"), MODULE);
                    return;
                }
                Debug.logInfo("Product Category created successfully with productCategoryId: " + productCategoryId, MODULE);
            }

            GenericValue existingProduct = EntityQuery.use(delegator)
                    .from("Product")
                    .where("productId", partNumberTo)
                    .queryOne();

            if (existingProduct == null) {
                Map<String, Object> productParams = UtilMisc.toMap(
                        "productId", partNumberTo,
                        "productTypeId", "FINISHED_GOOD",
                        "internalName", partNumberTo,
                        "quantityUomID", itemEquivalentUOM,
                        "userLogin", userLogin
                );

                Map<String, Object> productResult = dctx.getDispatcher().runSync("createProduct", productParams);

                if (!ServiceUtil.isSuccess(productResult)) {
                    Debug.logError("Error creating Product: " + productResult.get("errorMessage"), MODULE);
                    return;
                }
                Debug.logInfo("Product created successfully with productId: " + partNumberTo, MODULE);
            } else {
                Debug.logWarning("Product already exists: " + partNumberTo, MODULE);
            }

            Map<String, Object> productAssocParams = UtilMisc.toMap(
                    "productId", productId,
                    "productIdTo", partNumberTo,
                    "productAssocTypeId", "PRODUCT_SUBSTITUTE",
                    "fromDate", UtilDateTime.nowTimestamp(),
                    "quantity", interchangeQuantity,
                    "userLogin", userLogin
            );

            Map<String, Object> productAssocResult = dctx.getDispatcher().runSync("createProductAssoc", productAssocParams);

            if (!ServiceUtil.isSuccess(productAssocResult)) {
                Debug.logError("Error creating Product Association: " + productAssocResult.get("errorMessage"), MODULE);
                return;
            }
            Debug.logInfo("Product Association created successfully " + productId + "and" + partNumberTo, MODULE);

        } catch (Exception e) {
            Debug.logError(e, "Error creating PartInterchangeInfo", MODULE);
        }
    }

    private static String getAttributeValue(StartElement element, String attributeName) {
        Attribute attr = element.getAttributeByName(new QName(attributeName));
        return attr != null ? attr.getValue() : "";
    }

    private static String getCharacterData(XMLEventReader reader) throws XMLStreamException {
        String result = "";
        XMLEvent event = reader.nextEvent();
        //this is important to check if the event contains character or else it might throw an exception
        if (event instanceof Characters) {
            result = event.asCharacters().getData();
        }
        return result;
    }
}
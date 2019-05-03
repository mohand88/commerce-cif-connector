/*******************************************************************************
 *
 *    Copyright 2019 Adobe. All rights reserved.
 *    This file is licensed to you under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License. You may obtain a copy
 *    of the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software distributed under
 *    the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR REPRESENTATIONS
 *    OF ANY KIND, either express or implied. See the License for the specific language
 *    governing permissions and limitations under the License.
 *
 ******************************************************************************/

package com.adobe.cq.commerce.graphql.resource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.commons.scheduler.ScheduleOptions;
import org.apache.sling.commons.scheduler.Scheduler;
import org.apache.sling.spi.resource.provider.ResolveContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.adobe.cq.commerce.graphql.magento.GraphqlDataService;
import com.adobe.cq.commerce.graphql.magento.GraphqlDataServiceConfiguration;
import com.adobe.cq.commerce.magento.graphql.CategoryTree;
import com.adobe.cq.commerce.magento.graphql.ConfigurableProduct;
import com.adobe.cq.commerce.magento.graphql.ConfigurableVariant;
import com.adobe.cq.commerce.magento.graphql.ProductInterface;
import com.adobe.cq.commerce.magento.graphql.SimpleProduct;
import com.google.common.collect.Lists;

class ResourceMapper<T> {

    private static final Logger LOGGER = LoggerFactory.getLogger(ResourceMapper.class);

    private String root;
    private volatile boolean initDone = false;
    private volatile ReentrantLock initLock = new ReentrantLock();

    private volatile Map<String, CategoryTree> categoryByPaths;
    private volatile Map<Integer, String> categoryPathsById;

    private Scheduler scheduler;
    private GraphqlDataService graphqlClient;
    private GraphqlDataServiceConfiguration config;

    ResourceMapper(String root, GraphqlDataService graphqlClient, Scheduler scheduler) {
        this.root = root;
        this.scheduler = scheduler;
        this.graphqlClient = graphqlClient;
        this.config = graphqlClient.getConfiguration();

        if (config.catalogCachingEnabled() && config.catalogCachingSchedulerEnabled()) {
            scheduleCacheRefresh();
        }
    }

    /**
     * Schedules a periodic job to refresh the cache.
     */
    private void scheduleCacheRefresh() {
        final Runnable cacheRefreshJob = new Runnable() {
            public void run() {
                refreshCache();
            }
        };

        long period = graphqlClient.getConfiguration().catalogCachingTimeMinutes() * 60;
        ScheduleOptions opts = scheduler.NOW(-1, period).name("ResourceMapper.refreshCache");
        scheduler.schedule(cacheRefreshJob, opts);
    }

    /**
     * This method is used for 2 purposes:<br>
     * <ul>
     * <li>to initially build the cache in case the Sling scheduler has not yet started the <code>cacheRefreshJob</code></li>
     * <li>to build the "cache" for each method call in case caching is disabled (in which case the "cache" is of no use, except to build
     * all the category paths)</li>
     * </ul>
     */
    private void init() {
        if (!initDone) {
            try {
                // Block in case another thread is already initializing the cache
                initLock.lock();

                // The volatile keyword guarantees the happens-before "relationship"
                if (!initDone) {
                    refreshCache(); // If caching is disabled, repopulate the cache
                }
            } finally {
                initLock.unlock();
            }
        }
    }

    /**
     * Rebuilds the entire cache by calling {@link #buildAllCategoryPaths()}.
     */
    protected void refreshCache() {
        try {
            if (!initLock.tryLock()) {
                return; // Prevents the sling scheduler from refreshing the cache if init() was called first
            }
            LOGGER.debug("Fetching catalog and building categories cache");
            buildAllCategoryPaths();
            if (config.catalogCachingEnabled()) {
                initDone = true;
            }
        } finally {
            initLock.unlock();
        }
    }

    /**
     * This method builds various category caches used to lookup categories and products in a faster way.
     */
    private void buildAllCategoryPaths() {
        CategoryTree categoryTree = graphqlClient.getCategoryTree(config.rootCategoryId());
        if (categoryTree == null || CollectionUtils.isEmpty(categoryTree.getChildren())) {
            LOGGER.error("The Magento catalog is null or empty");
            return;
        }

        // We first build all the category paths
        Map<String, CategoryTree> categoryByPathsMap = new HashMap<>();
        Map<Integer, String> categoryPathsByIdMap = new HashMap<>();

        // We insert the root with an empty path
        categoryByPathsMap.put("", categoryTree);
        categoryPathsByIdMap.put(categoryTree.getId(), "");

        // Add all children
        for (CategoryTree child : categoryTree.getChildren()) {
            buildAllCategoryPathsFor(child, categoryByPathsMap, categoryPathsByIdMap);
        }

        // Because of a bug in Magento 2.3.0 GraphQL "categories" query, we fix the tree of categories
        checkMagentoCategoryTreeMap(categoryByPathsMap);

        categoryByPaths = categoryByPathsMap;
        categoryPathsById = categoryPathsByIdMap;
    }

    private void checkMagentoCategoryTreeMap(Map<String, CategoryTree> categoryByPathsMap) {
        // We first add each category in its right parent
        for (CategoryTree categoryTree : categoryByPathsMap.values()) {
            if (StringUtils.contains(categoryTree.getUrlPath(), "/")) {
                String parentUrlPath = StringUtils.substringBeforeLast(categoryTree.getUrlPath(), "/");
                CategoryTree parent = categoryByPathsMap.get(parentUrlPath);
                if (parent != null) {
                    if (parent.getChildren() == null) {
                        List<CategoryTree> children = new LinkedList<>();
                        children.add(categoryTree);
                        parent.setChildren(children);
                    } else if (!parent.getChildren().stream().anyMatch(c -> c.getId().equals(categoryTree.getId()))) {
                        parent.getChildren().add(categoryTree);
                    }

                }
            }
        }

        // We then filter all the children
        for (CategoryTree categoryTree : categoryByPathsMap.values()) {
            List<CategoryTree> children = categoryTree.getChildren();
            if (CollectionUtils.isNotEmpty(children) && StringUtils.isNotEmpty(categoryTree.getUrlPath())) {
                categoryTree.setChildren(children.stream().filter(c -> c.getUrlPath().startsWith(categoryTree.getUrlPath())).collect(
                    Collectors.toList()));
            }
        }
    }

    private void buildAllCategoryPathsFor(CategoryTree categoryTree, Map<String, CategoryTree> categoryByPathsMap,
        Map<Integer, String> categoryPathsByIdMap) {
        LOGGER.debug("Adding cached category " + categoryTree.getId() + " --> " + categoryTree.getUrlPath());
        categoryByPathsMap.put(categoryTree.getUrlPath(), categoryTree);
        categoryPathsByIdMap.put(categoryTree.getId(), categoryTree.getUrlPath());
        for (CategoryTree child : categoryTree.getChildren()) {
            buildAllCategoryPathsFor(child, categoryByPathsMap, categoryPathsByIdMap);
        }
    }

    String getAbsoluteCategoryPath(String categoryId) {
        init();
        return root + "/" + categoryPathsById.get(Integer.valueOf(categoryId));
    }

    String getRoot() {
        return root;
    }

    CategoryResource resolveCategory(ResolveContext<T> ctx, String path) {
        init();

        // We lookup the category path in the cache
        // Example for path: /var/commerce/products/cloudcommerce/Men/Coats
        // Remove root (/var/commerce/products/cloudcommerce) then try to find the category path /Men/Coats

        String subPath = path.substring(root.length() + 1);
        CategoryTree category = categoryByPaths.get(subPath);
        if (category != null) {
            return new CategoryResource(ctx.getResourceResolver(), path, category);
        }
        return null;
    }

    ProductResource resolveProduct(ResolveContext<T> ctx, String path) {
        List<String> productParts = resolveProductParts(path);

        try {

            // We get productParts[] like:
            // Variant lookup: [meskwielt, meskwielt.2-l]
            // Product lookup: [meskwielt]
            // --> we always use the SKU of the base/parent product to get the product
            // --> and use the 2nd part (if any) to select the right variant

            String sku = productParts.get(0);
            ProductInterface product = graphqlClient.getProductBySku(sku);
            if (product != null && product.getId() != null) {
                boolean isVariant = productParts.size() > 1;
                return new ProductResource(ctx.getResourceResolver(), path, product, isVariant ? productParts.get(1) : null);
            }
        } catch (Exception e) {
            LOGGER.error("Error while fetching category products", e);
            return null;
        }

        return null;
    }

    SyntheticImageResource resolveProductImage(ResolveContext<T> ctx, String path) {
        String productPath = path.substring(0, path.length() - "/image".length());
        List<String> productParts = resolveProductParts(productPath);
        try {
            String sku = productParts.size() == 1 ? productParts.get(0) : productParts.get(1);
            ProductInterface product = graphqlClient.getProductBySku(sku);
            if (product != null) {
                String imageUrl = product.getImage().getUrl();
                if (imageUrl == null && product instanceof ConfigurableProduct) {
                    ConfigurableProduct cp = (ConfigurableProduct) product;
                    if (cp.getVariants() != null && cp.getVariants().size() > 0) {
                        imageUrl = cp.getVariants().get(0).getProduct().getImage().getUrl();
                    }
                }

                if (imageUrl != null) {
                    return new SyntheticImageResource(ctx.getResourceResolver(), path, SyntheticImageResource.IMAGE_RESOURCE_TYPE,
                        imageUrl);
                } else {
                    return null;
                }
            }
        } catch (Exception e) {
            LOGGER.error("Error while fetching category products", e);
            return null;
        }

        return null;
    }

    private List<String> resolveProductParts(String path) {
        init();

        // To speedup the lookup, we try to find the longest possible category path
        // Example for path: /var/commerce/products/cloudcommerce/Men/Coats/meskwielt.1-s
        // Remove root (/var/commerce/products/cloudcommerce) then try to find category /Men/Coats
        // --> we find the category /Men/Coats and try to fetch the product meskwielt.1-s

        // Example for path: /var/commerce/products/cloudcommerce/Men/Coats/meskwielt.1-s/meskwielt.2-l
        // Remove root (/var/commerce/products/cloudcommerce) then try to find category /Men/Coats/meskwielt.1-s
        // --> that category is not found, so we try to find the category /Men/Coats
        // --> we find the category /Men/Coats and try to fetch the product meskwielt.1-s and variant meskwielt.2-l

        String subPath = path.substring(root.length() + 1);
        int backtrackCounter = 0;
        List<String> productParts = new ArrayList<>();
        String[] parts = subPath.split("/");
        Set<String> categoryPaths = categoryByPaths.keySet(); // defensive copy in case the cache is updated while looping
        for (String part : Lists.reverse(Arrays.asList(parts))) {
            productParts.add(part);
            backtrackCounter -= part.length() + 1;
            String categorySubPath = StringUtils.substring(subPath, 0, backtrackCounter);
            if (categoryPaths.contains(categorySubPath)) {
                break;
            }
        }
        productParts = Lists.reverse(productParts);
        return productParts;
    }

    Iterator<Resource> listCategoryChildren(ResolveContext<T> ctx, Resource parent) {
        init();

        String parentPath = parent.getPath();
        String parentCifId = parent.getValueMap().get(Constants.CIF_ID, String.class);
        boolean isRoot = parentPath.equals(root);
        String key = isRoot ? "" : parentPath.substring(root.length() + 1);
        ResourceResolver resolver = ctx.getResourceResolver();
        List<Resource> children = new ArrayList<>();

        CategoryTree categoryTree = categoryByPaths.get(key);
        if (categoryTree != null) {
            for (CategoryTree child : categoryTree.getChildren()) {
                children.add(new CategoryResource(resolver, root + "/" + child.getUrlPath(), child));
            }
        }

        if (children.isEmpty()) {
            try {
                List<ProductInterface> products = graphqlClient.getCategoryProducts(Integer.valueOf(parentCifId));
                if (products != null && !products.isEmpty()) {
                    for (ProductInterface product : products) {
                        String path = parentPath + "/" + product.getSku();
                        children.add(new ProductResource(resolver, path, product));
                    }
                }
            } catch (Exception e) {
                LOGGER.error("Error while fetching category products for " + parentPath + " (" + parentCifId + ")", e);
            }
        }

        return children.isEmpty() ? null : children.iterator();
    }

    Iterator<Resource> listProductChildren(ResolveContext<T> ctx, Resource parent) {
        init();

        String sku = parent.getValueMap().get(Constants.SKU, String.class);
        String parentPath = parent.getPath();

        try {
            ProductInterface productInterface = graphqlClient.getProductBySku(sku);
            if (productInterface != null && productInterface instanceof ConfigurableProduct) {
                ConfigurableProduct product = (ConfigurableProduct) productInterface;
                List<ConfigurableVariant> variants = product.getVariants();
                if (variants != null && !variants.isEmpty()) {
                    List<Resource> children = new ArrayList<>();
                    ResourceResolver resolver = ctx.getResourceResolver();
                    String imageUrl = product.getImage().getUrl();
                    for (ConfigurableVariant variant : variants) {
                        SimpleProduct simpleProduct = variant.getProduct();
                        String path = parentPath + "/" + simpleProduct.getSku();
                        children.add(new ProductResource(resolver, path, simpleProduct, simpleProduct.getSku()));

                        if (imageUrl == null) {
                            imageUrl = simpleProduct.getImage().getUrl();
                        }
                    }

                    if (imageUrl != null) {
                        String imagePath = parentPath + "/image";
                        Resource imageResource = new SyntheticImageResource(ctx.getResourceResolver(), imagePath,
                            SyntheticImageResource.IMAGE_RESOURCE_TYPE, imageUrl);
                        children.add(0, imageResource);
                    }

                    return children.iterator();
                }
            }
        } catch (Exception e) {
            LOGGER.error("Error while fetching variants for product " + sku, e);
            return null;
        }

        return null;
    }
}
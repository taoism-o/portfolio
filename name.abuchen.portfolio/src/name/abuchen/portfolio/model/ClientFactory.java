package name.abuchen.portfolio.model;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.text.MessageFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import name.abuchen.portfolio.Messages;
import name.abuchen.portfolio.model.Classification.Assignment;
import name.abuchen.portfolio.model.PortfolioTransaction.Type;
import name.abuchen.portfolio.money.CurrencyUnit;
import name.abuchen.portfolio.money.Values;
import name.abuchen.portfolio.online.impl.YahooFinanceQuoteFeed;
import name.abuchen.portfolio.util.ProgressMonitorInputStream;

import org.eclipse.core.runtime.IProgressMonitor;

import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.XStreamException;
import com.thoughtworks.xstream.converters.basic.DateConverter;

@SuppressWarnings("deprecation")
public class ClientFactory
{
    private static class XmlSerialization
    {
        public Client load(Reader input) throws IOException
        {
            try
            {
                Client client = (Client) xstream().fromXML(input);

                if (client.getVersion() > Client.CURRENT_VERSION)
                    throw new IOException(MessageFormat.format(Messages.MsgUnsupportedVersionClientFiled,
                                    client.getVersion()));

                upgradeModel(client);

                return client;
            }
            catch (XStreamException e)
            {
                throw new IOException(MessageFormat.format(Messages.MsgXMLFormatInvalid, e.getMessage()), e);
            }
        }

        void save(Client client, OutputStream output) throws IOException
        {
            Writer writer = new OutputStreamWriter(output, StandardCharsets.UTF_8);

            xstream().toXML(client, writer);

            writer.flush();
        }
    }

    private interface ClientPersister
    {
        Client load(InputStream input) throws IOException;

        void save(Client client, OutputStream output) throws IOException;
    }

    private static class PlainWriter implements ClientPersister
    {
        @Override
        public Client load(InputStream input) throws IOException
        {
            return new XmlSerialization().load(new InputStreamReader(input, StandardCharsets.UTF_8));
        }

        @Override
        public void save(Client client, OutputStream output) throws IOException
        {
            new XmlSerialization().save(client, output);
        }
    }

    private static class Decryptor implements ClientPersister
    {
        private static final byte[] SIGNATURE = new byte[] { 'P', 'O', 'R', 'T', 'F', 'O', 'L', 'I', 'O' };

        private static final byte[] SALT = new byte[] { 112, 67, 103, 107, -92, -125, -112, -95, //
                        -97, -114, 117, -56, -53, -69, -25, -28 };

        private static final String AES = "AES"; //$NON-NLS-1$
        private static final String CIPHER_ALGORITHM = "AES/CBC/PKCS5Padding"; //$NON-NLS-1$
        private static final String SECRET_KEY_ALGORITHM = "PBKDF2WithHmacSHA1"; //$NON-NLS-1$
        private static final int ITERATION_COUNT = 65536;
        private static final int IV_LENGTH = 16;

        private static final int AES128_KEYLENGTH = 128;
        private static final int AES256_KEYLENGTH = 256;

        private char[] password;
        private int keyLength;

        public Decryptor(String method, char[] password)
        {
            this.password = password;
            this.keyLength = "AES256".equals(method) ? AES256_KEYLENGTH : AES128_KEYLENGTH; //$NON-NLS-1$
        }

        @Override
        public Client load(final InputStream input) throws IOException
        {
            InputStream decrypted = null;

            try
            {
                // check signature
                byte[] signature = new byte[SIGNATURE.length];
                input.read(signature);
                if (!Arrays.equals(signature, SIGNATURE))
                    throw new IOException(Messages.MsgNotAPortflioFile);

                // read encryption method
                int method = input.read();
                this.keyLength = method == 1 ? AES256_KEYLENGTH : AES128_KEYLENGTH;

                // check if key length is supported
                if (!isKeyLengthSupported(this.keyLength))
                    throw new IOException(Messages.MsgKeyLengthNotSupported);

                // build secret key
                SecretKey secret = buildSecretKey();

                // read initialization vector
                byte[] iv = new byte[IV_LENGTH];
                input.read(iv);

                // build cipher and stream
                Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
                cipher.init(Cipher.DECRYPT_MODE, secret, new IvParameterSpec(iv));
                decrypted = new CipherInputStream(input, cipher);

                // read version information
                byte[] bytes = new byte[4];
                decrypted.read(bytes); // major version number
                int majorVersion = ByteBuffer.wrap(bytes).getInt();
                decrypted.read(bytes); // version number
                int version = ByteBuffer.wrap(bytes).getInt();

                if (majorVersion > Client.MAJOR_VERSION || version > Client.CURRENT_VERSION)
                    throw new IOException(MessageFormat.format(Messages.MsgUnsupportedVersionClientFiled, version));

                // wrap with zip input stream
                ZipInputStream zipin = new ZipInputStream(decrypted);
                zipin.getNextEntry();

                Client client = new XmlSerialization().load(new InputStreamReader(zipin, StandardCharsets.UTF_8));

                // save secret key for next save
                client.setSecret(secret);

                return client;
            }
            catch (GeneralSecurityException e)
            {
                throw new IOException(MessageFormat.format(Messages.MsgErrorDecrypting, e.getMessage()), e);
            }
            finally
            {
                try
                {
                    if (decrypted != null)
                        decrypted.close();
                }
                catch (IOException ignore)
                {
                    // starting with a later jdk 1.8.0 (for example 1.8.0_25), a
                    // javax.crypto.BadPaddingException
                    // "Given final block not properly padded" is thrown if the
                    // we do not read the complete stream
                }
            }
        }

        @Override
        public void save(Client client, final OutputStream output) throws IOException
        {
            try
            {
                // check if key length is supported
                if (!isKeyLengthSupported(this.keyLength))
                    throw new IOException(Messages.MsgKeyLengthNotSupported);

                // get or build secret key
                // if password is given, it is used (when the user chooses
                // "save as" from the menu)
                SecretKey secret = password != null ? buildSecretKey() : client.getSecret();
                if (secret == null)
                    throw new IOException(Messages.MsgPasswordMissing);

                // save secret key for next save
                client.setSecret(secret);

                // write signature
                output.write(SIGNATURE);

                // write method
                output.write(secret.getEncoded().length * 8 == AES256_KEYLENGTH ? 1 : 0);

                // build cipher and stream
                Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
                cipher.init(Cipher.ENCRYPT_MODE, secret);

                // write initialization vector
                AlgorithmParameters params = cipher.getParameters();
                byte[] iv = params.getParameterSpec(IvParameterSpec.class).getIV();
                output.write(iv);

                // encrypted stream
                OutputStream encrpyted = new CipherOutputStream(output, cipher);

                // write version information
                encrpyted.write(ByteBuffer.allocate(4).putInt(Client.MAJOR_VERSION).array());
                encrpyted.write(ByteBuffer.allocate(4).putInt(client.getVersion()).array());

                // wrap with zip output stream
                ZipOutputStream zipout = new ZipOutputStream(encrpyted);
                zipout.putNextEntry(new ZipEntry("data.xml")); //$NON-NLS-1$

                new XmlSerialization().save(client, zipout);

                zipout.closeEntry();
                zipout.flush();
                zipout.finish();
                output.flush();
            }
            catch (GeneralSecurityException e)
            {
                throw new IOException(MessageFormat.format(Messages.MsgErrorEncrypting, e.getMessage()), e);
            }
        }

        private SecretKey buildSecretKey() throws NoSuchAlgorithmException, InvalidKeySpecException
        {
            SecretKeyFactory factory = SecretKeyFactory.getInstance(SECRET_KEY_ALGORITHM);
            KeySpec spec = new PBEKeySpec(password, SALT, ITERATION_COUNT, keyLength);
            SecretKey tmp = factory.generateSecret(spec);
            return new SecretKeySpec(tmp.getEncoded(), AES);
        }
    }

    private static XStream xstream;

    public static boolean isEncrypted(File file)
    {
        return file.getName().endsWith(".portfolio"); //$NON-NLS-1$
    }

    public static boolean isKeyLengthSupported(int keyLength)
    {
        try
        {
            return keyLength <= Cipher.getMaxAllowedKeyLength(Decryptor.CIPHER_ALGORITHM);
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new IllegalArgumentException(MessageFormat.format(Messages.MsgErrorEncrypting, e.getMessage()), e);
        }
    }

    public static Client load(File file, char[] password, IProgressMonitor monitor) throws IOException
    {
        if (isEncrypted(file) && password == null)
            throw new IOException(Messages.MsgPasswordMissing);

        InputStream input = null;

        try
        {
            // progress monitor
            long bytesTotal = file.length();
            int increment = (int) Math.min(bytesTotal / 20L, Integer.MAX_VALUE);
            monitor.beginTask(MessageFormat.format(Messages.MsgReadingFile, file.getName()), 20);
            input = new ProgressMonitorInputStream(new FileInputStream(file), increment, monitor);

            return buildPersister(file, null, password).load(input);
        }
        catch (FileNotFoundException e)
        {
            throw new IOException(MessageFormat.format(Messages.MsgFileNotFound, file.getAbsolutePath()), e);
        }
        finally
        {
            if (input != null)
                input.close();
        }
    }

    public static Client load(Reader input) throws IOException
    {
        try
        {
            return new XmlSerialization().load(input);
        }
        finally
        {
            if (input != null)
                input.close();
        }
    }

    public static Client load(InputStream input) throws IOException
    {
        return load(new InputStreamReader(input, StandardCharsets.UTF_8));
    }

    public static void save(final Client client, final File file, String method, char[] password) throws IOException
    {
        if (isEncrypted(file) && password == null && client.getSecret() == null)
            throw new IOException(Messages.MsgPasswordMissing);

        OutputStream output = null;

        try
        {
            output = new FileOutputStream(file);

            buildPersister(file, method, password).save(client, output);
        }
        finally
        {
            if (output != null)
                output.close();
        }
    }

    private static ClientPersister buildPersister(File file, String method, char[] password)
    {
        if (file != null && isEncrypted(file))
            return new Decryptor(method, password);
        else
            return new PlainWriter();
    }

    private static void upgradeModel(Client client)
    {
        client.doPostLoadInitialization();

        client.setFileVersionAfterRead(client.getVersion());

        switch (client.getVersion())
        {
            case 1:
                fixAssetClassTypes(client);
                addFeedAndExchange(client);
            case 2:
                addDecimalPlaces(client);
            case 3:
                // do nothing --> added industry classification
            case 4:
                for (Security s : client.getSecurities())
                    s.generateUUID();
            case 5:
                // do nothing --> save industry taxonomy in client
            case 6:
                // do nothing --> added WKN attribute to security
            case 7:
                // new portfolio transaction types:
                // DELIVERY_INBOUND, DELIVERY_OUTBOUND
                changePortfolioTransactionTypeToDelivery(client);
            case 8:
                // do nothing --> added 'retired' property to securities
            case 9:
                // do nothing --> added 'cross entries' to transactions
            case 10:
                generateUUIDs(client);
            case 11:
                // do nothing --> added 'properties' to client
            case 12:
                // added investment plans
                // added security on chart as benchmark *and* performance
                fixStoredBenchmarkChartConfigurations(client);
            case 13:
                // introduce arbitrary taxonomies
                addAssetClassesAsTaxonomy(client);
                addIndustryClassificationAsTaxonomy(client);
                addAssetAllocationAsTaxonomy(client);
                fixStoredClassificationChartConfiguration(client);
                setDeprecatedFieldsToNull(client);
            case 14:
                // added shares to track dividends per share
                assignSharesToDividendTransactions(client);
            case 15:
                // do nothing --> added 'isRetired' property to account
            case 16:
                // do nothing --> added 'feedURL' property to account
            case 17:
                // do nothing --> added notes attribute
            case 18:
                // do nothing --> added events (stock split) to securities
            case 19:
                // do nothing --> added attribute types
            case 20:
                // do nothing --> added note to investment plan
            case 21:
                // do nothing --> added taxes to portfolio transaction
            case 22:
                // do nothing --> added 'isRetired' property to portfolio
            case 23:
                // do nothing --> added 'latestFeed' and 'latestFeedURL'
                // property to security
            case 24:
                // do nothing --> added 'TAX_REFUND' as account transaction
            case 25:
                // incremented precision of shares to 6 digits after the decimal
                // sign
                incrementSharesPrecisionFromFiveToSixDigitsAfterDecimalSign(client);
            case 26:
                // do nothing --> added client settings
            case 27:
                // added currency support --> designate a default currency (user
                // will get a dialog to change)
                setAllCurrencies(client, CurrencyUnit.EUR);
                client.setVersion(Client.CURRENT_VERSION);
                break;
            case Client.CURRENT_VERSION:
                break;
            default:
                break;
        }
    }

    private static void fixAssetClassTypes(Client client)
    {
        for (Security security : client.getSecurities())
        {
            if ("STOCK".equals(security.getType())) //$NON-NLS-1$
                security.setType("EQUITY"); //$NON-NLS-1$
            else if ("BOND".equals(security.getType())) //$NON-NLS-1$
                security.setType("DEBT"); //$NON-NLS-1$
        }
    }

    private static void addFeedAndExchange(Client client)
    {
        for (Security s : client.getSecurities())
            s.setFeed(YahooFinanceQuoteFeed.ID);
    }

    private static void addDecimalPlaces(Client client)
    {
        for (Portfolio p : client.getPortfolios())
            for (PortfolioTransaction t : p.getTransactions())
                t.setShares(t.getShares() * Values.Share.factor());
    }

    private static void changePortfolioTransactionTypeToDelivery(Client client)
    {
        for (Portfolio p : client.getPortfolios())
        {
            for (PortfolioTransaction t : p.getTransactions())
            {
                if (t.getType() == Type.TRANSFER_IN)
                    t.setType(Type.DELIVERY_INBOUND);
                else if (t.getType() == Type.TRANSFER_OUT)
                    t.setType(Type.DELIVERY_OUTBOUND);
            }
        }
    }

    private static void generateUUIDs(Client client)
    {
        for (Account a : client.getAccounts())
            a.generateUUID();
        for (Portfolio p : client.getPortfolios())
            p.generateUUID();
        for (Category c : client.getRootCategory().flatten())
            c.generateUUID();
    }

    @SuppressWarnings("nls")
    private static void fixStoredBenchmarkChartConfigurations(Client client)
    {
        // Until now, the performance chart was showing *only* the benchmark
        // series, not the actual performance series. Change keys as benchmark
        // values are prefixed with '[b]'

        replace(client, "PerformanceChartView-PICKER", //
                        "Security", "[b]Security", //
                        "ConsumerPriceIndex", "[b]ConsumerPriceIndex");
    }

    private static void addAssetClassesAsTaxonomy(Client client)
    {
        TaxonomyTemplate template = TaxonomyTemplate.byId("assetclasses"); //$NON-NLS-1$
        Taxonomy taxonomy = template.buildFromTemplate();
        taxonomy.setId("assetclasses"); //$NON-NLS-1$

        int rank = 1;

        Classification cash = taxonomy.getClassificationById("CASH"); //$NON-NLS-1$
        for (Account account : client.getAccounts())
        {
            Assignment assignment = new Assignment(account);
            assignment.setRank(rank++);
            cash.addAssignment(assignment);
        }

        for (Security security : client.getSecurities())
        {
            Classification classification = taxonomy.getClassificationById(security.getType());

            if (classification != null)
            {
                Assignment assignment = new Assignment(security);
                assignment.setRank(rank++);
                classification.addAssignment(assignment);
            }
        }

        client.addTaxonomy(taxonomy);
    }

    private static void addIndustryClassificationAsTaxonomy(Client client)
    {
        String oldIndustryId = client.getIndustryTaxonomy();

        Taxonomy taxonomy = null;

        if ("simple2level".equals(oldIndustryId)) //$NON-NLS-1$
            taxonomy = TaxonomyTemplate.byId(TaxonomyTemplate.INDUSTRY_SIMPLE2LEVEL).buildFromTemplate();
        else
            taxonomy = TaxonomyTemplate.byId(TaxonomyTemplate.INDUSTRY_GICS).buildFromTemplate();

        taxonomy.setId("industries"); //$NON-NLS-1$

        // add industry taxonomy only if at least one security has been assigned
        if (assignSecurities(client, taxonomy))
            client.addTaxonomy(taxonomy);
    }

    private static boolean assignSecurities(Client client, Taxonomy taxonomy)
    {
        boolean hasAssignments = false;

        int rank = 0;
        for (Security security : client.getSecurities())
        {
            Classification classification = taxonomy.getClassificationById(security.getIndustryClassification());

            if (classification != null)
            {
                Assignment assignment = new Assignment(security);
                assignment.setRank(rank++);
                classification.addAssignment(assignment);

                hasAssignments = true;
            }
        }

        return hasAssignments;
    }

    private static void addAssetAllocationAsTaxonomy(Client client)
    {
        Category category = client.getRootCategory();

        Taxonomy taxonomy = new Taxonomy("assetallocation", Messages.LabelAssetAllocation); //$NON-NLS-1$
        Classification root = new Classification(category.getUUID(), Messages.LabelAssetAllocation);
        taxonomy.setRootNode(root);

        buildTree(root, category);

        root.assignRandomColors();

        client.addTaxonomy(taxonomy);
    }

    private static void buildTree(Classification node, Category category)
    {
        int rank = 0;

        for (Category child : category.getChildren())
        {
            Classification classification = new Classification(node, child.getUUID(), child.getName());
            classification.setWeight(child.getPercentage() * Values.Weight.factor());
            classification.setRank(rank++);
            node.addChild(classification);

            buildTree(classification, child);
        }

        for (Object element : category.getElements())
        {
            Assignment assignment = element instanceof Account ? new Assignment((Account) element) : new Assignment(
                            (Security) element);
            assignment.setRank(rank++);

            node.addAssignment(assignment);
        }
    }

    @SuppressWarnings("nls")
    private static void fixStoredClassificationChartConfiguration(Client client)
    {
        String name = Classification.class.getSimpleName();
        replace(client, "PerformanceChartView-PICKER", //
                        "AssetClass", name, //
                        "Category", name);

        replace(client, "StatementOfAssetsHistoryView-PICKER", //
                        "AssetClass", name, //
                        "Category", name);
    }

    private static void replace(Client client, String property, String... replacements)
    {
        if (replacements.length % 2 != 0)
            throw new UnsupportedOperationException();

        String value = client.getProperty(property);
        if (value != null)
            replaceAll(client, property, value, replacements);

        int index = 0;
        while (true)
        {
            String key = property + '$' + index;
            value = client.getProperty(key);
            if (value != null)
                replaceAll(client, key, value, replacements);
            else
                break;

            index++;
        }
    }

    private static void replaceAll(Client client, String key, String value, String[] replacements)
    {
        String newValue = value;
        for (int ii = 0; ii < replacements.length; ii += 2)
            newValue = newValue.replaceAll(replacements[ii], replacements[ii + 1]);
        client.setProperty(key, newValue);
    }

    private static void setDeprecatedFieldsToNull(Client client)
    {
        client.setRootCategory(null);
        client.setIndustryTaxonomy(null);

        for (Security security : client.getSecurities())
        {
            security.setIndustryClassification(null);
            security.setType(null);
        }
    }

    private static void assignSharesToDividendTransactions(Client client)
    {
        for (Security security : client.getSecurities())
        {
            List<TransactionPair<?>> transactions = security.getTransactions(client);

            // sort by date of transaction
            Collections.sort(transactions, new Comparator<TransactionPair<?>>()
            {
                @Override
                public int compare(TransactionPair<?> one, TransactionPair<?> two)
                {
                    return one.getTransaction().getDate().compareTo(two.getTransaction().getDate());
                }
            });

            // count and assign number of shares by account
            Map<Account, Long> account2shares = new HashMap<Account, Long>();
            for (TransactionPair<? extends Transaction> t : transactions)
            {
                if (t.getTransaction() instanceof AccountTransaction)
                {
                    AccountTransaction accountTransaction = (AccountTransaction) t.getTransaction();

                    switch (accountTransaction.getType())
                    {
                        case DIVIDENDS:
                        case INTEREST:
                            Long shares = account2shares.get(t.getOwner());
                            accountTransaction.setShares(shares != null ? shares : 0);
                            break;
                        default:
                    }
                }
                else if (t.getTransaction() instanceof PortfolioTransaction)
                {
                    PortfolioTransaction portfolioTransaction = (PortfolioTransaction) t.getTransaction();

                    // determine account: if it exists, take the cross entry;
                    // otherwise the reference account
                    Account account = null;
                    switch (portfolioTransaction.getType())
                    {
                        case BUY:
                        case SELL:
                            if (portfolioTransaction.getCrossEntry() != null)
                                account = (Account) portfolioTransaction.getCrossEntry().getCrossOwner(
                                                portfolioTransaction);
                        case TRANSFER_IN:
                        case TRANSFER_OUT:
                        default:
                            if (account == null)
                                account = ((Portfolio) t.getOwner()).getReferenceAccount();
                    }

                    long delta = 0;
                    switch (portfolioTransaction.getType())
                    {
                        case BUY:
                        case TRANSFER_IN:
                            delta = portfolioTransaction.getShares();
                            break;
                        case SELL:
                        case TRANSFER_OUT:
                            delta = -portfolioTransaction.getShares();
                            break;
                        default:
                            break;
                    }

                    Long shares = account2shares.get(account);
                    account2shares.put(account, shares != null ? shares + delta : delta);
                }
            }
        }
    }

    private static void incrementSharesPrecisionFromFiveToSixDigitsAfterDecimalSign(Client client)
    {
        for (Portfolio portfolio : client.getPortfolios())
            for (PortfolioTransaction portfolioTransaction : portfolio.getTransactions())
                portfolioTransaction.setShares(portfolioTransaction.getShares() * 10);
        for (Account account : client.getAccounts())
            for (AccountTransaction accountTransaction : account.getTransactions())
                accountTransaction.setShares(accountTransaction.getShares() * 10);
    }

    public static void setAllCurrencies(Client client, String currencyCode)
    {
        client.setBaseCurrency(currencyCode);
        client.getAccounts().stream().forEach(a -> a.setCurrencyCode(currencyCode));
        client.getSecurities().stream().forEach(s -> s.setCurrencyCode(currencyCode));

        client.getAccounts().stream().flatMap(a -> a.getTransactions().stream())
                        .forEach(t -> t.setCurrencyCode(currencyCode));
        client.getPortfolios().stream().flatMap(p -> p.getTransactions().stream())
                        .forEach(t -> t.setCurrencyCode(currencyCode));
    }

    @SuppressWarnings("nls")
    private static XStream xstream()
    {
        if (xstream == null)
        {
            synchronized (ClientFactory.class)
            {
                if (xstream == null)
                {
                    xstream = new XStream();

                    xstream.setClassLoader(ClientFactory.class.getClassLoader());

                    xstream.alias("account", Account.class);
                    xstream.alias("client", Client.class);
                    xstream.alias("settings", ClientSettings.class);
                    xstream.alias("bookmark", Bookmark.class);
                    xstream.alias("portfolio", Portfolio.class);
                    xstream.alias("account-transaction", AccountTransaction.class);
                    xstream.alias("portfolio-transaction", PortfolioTransaction.class);
                    xstream.alias("security", Security.class);
                    xstream.alias("latest", LatestSecurityPrice.class);
                    xstream.alias("category", Category.class);
                    xstream.alias("watchlist", Watchlist.class);
                    xstream.alias("investment-plan", InvestmentPlan.class);

                    xstream.alias("price", SecurityPrice.class);
                    xstream.useAttributeFor(SecurityPrice.class, "time");
                    xstream.aliasField("t", SecurityPrice.class, "time");
                    xstream.useAttributeFor(SecurityPrice.class, "value");
                    xstream.aliasField("v", SecurityPrice.class, "value");

                    xstream.alias("cpi", ConsumerPriceIndex.class);
                    xstream.useAttributeFor(ConsumerPriceIndex.class, "year");
                    xstream.aliasField("y", ConsumerPriceIndex.class, "year");
                    xstream.useAttributeFor(ConsumerPriceIndex.class, "month");
                    xstream.aliasField("m", ConsumerPriceIndex.class, "month");
                    xstream.useAttributeFor(ConsumerPriceIndex.class, "index");
                    xstream.aliasField("i", ConsumerPriceIndex.class, "index");

                    xstream.registerConverter(new DateConverter("yyyy-MM-dd", new String[] { "yyyy-MM-dd" }, Calendar
                                    .getInstance().getTimeZone()));

                    xstream.alias("buysell", BuySellEntry.class);
                    xstream.alias("account-transfer", AccountTransferEntry.class);
                    xstream.alias("portfolio-transfer", PortfolioTransferEntry.class);

                    xstream.alias("taxonomy", Taxonomy.class);
                    xstream.alias("classification", Classification.class);
                    xstream.alias("assignment", Assignment.class);

                    xstream.alias("event", SecurityEvent.class);
                    xstream.alias("forex", ForexData.class);
                }
            }
        }
        return xstream;
    }
}

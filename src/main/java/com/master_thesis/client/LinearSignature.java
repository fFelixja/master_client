package com.master_thesis.client;

import ch.qos.logback.classic.Logger;
import com.master_thesis.client.data.Construction;
import com.master_thesis.client.data.LinearSignatureData;
import com.master_thesis.client.data.LinearSignatureData.PublicData;
import com.master_thesis.client.data.LinearSignatureData.ServerData;
import com.master_thesis.client.data.Server;
import com.master_thesis.client.util.PublicParameters;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.net.URI;
import java.security.SecureRandom;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Component
public class LinearSignature {
    private static final Logger log = (Logger) LoggerFactory.getLogger(LinearSignature.class);
    private PublicParameters publicParameters;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public LinearSignature(PublicParameters publicParameters) {
        this.publicParameters = publicParameters;
    }

    public LinearSignatureData shareSecret(int int_secret) {
        // Collect public information
        int substationID = publicParameters.getSubstationID();
        BigInteger fieldBase = publicParameters.getFieldBase(substationID);

        // Generate client specific things
        BigInteger secret = BigInteger.valueOf(int_secret);
        BigInteger nonce = BigInteger.valueOf(random.nextLong()).mod(fieldBase);
        Function<Integer, BigInteger> polynomial = generatePolynomial(int_secret, fieldBase);

        log.info("base: {}, secret: {}, nonce: {}", fieldBase, secret, nonce);

        // Compute server specific things
        List<Server> servers = publicParameters.getServers();
        Set<Integer> polynomialInput = IntStream.range(1,servers.size() + 1).boxed().collect(Collectors.toSet());
        Iterator<Integer> iteratorPolyInput = polynomialInput.iterator();
        Map<URI, ServerData> shares = new HashMap<>();
        servers.forEach(server -> {
            int number = iteratorPolyInput.next();
            BigInteger share = polynomial.apply(number);
            share = share.multiply(beta(number, polynomialInput));
            shares.put(server.getUri().resolve(Construction.LINEAR.getEndpoint()), new ServerData(share));
        });
        return new LinearSignatureData(shares, nonce);
    }

    protected Function<Integer, BigInteger> generatePolynomial(int secret, BigInteger field) {
        int t = publicParameters.getSecurityThreshold();
        StringBuilder logString = new StringBuilder("Polynomial used: ").append(secret);
        ArrayList<BigInteger> coefficients = new ArrayList<>();
        for (int i = 1; i <= t; i++) {
            BigInteger a;
            do {
                a = new BigInteger(field.bitLength(), random).mod(field);
            } while (a.equals(BigInteger.ZERO) || a.compareTo(field) >= 0);
            logString.append(" + ").append(a).append("x^").append(i);
            coefficients.add(a);
        }
        log.info(logString.toString());

        return (serverID) -> {
            BigInteger serverIDBIG = BigInteger.valueOf(serverID);
            BigInteger res = BigInteger.valueOf(secret);
            for (int i = 0; i < coefficients.size(); i++) {
                BigInteger coefficient = coefficients.get(i);
                BigInteger polynomial = serverIDBIG.pow(i + 1);
                res = res.add(coefficient.multiply(polynomial));
            }
            return res;
        };
    }

    public BigInteger beta(int currentValue, Set<Integer> potentialValues){
        BigInteger cv = BigInteger.valueOf(currentValue);
        BigInteger nominator = potentialValues.stream().map(BigInteger::valueOf)
                .filter(x -> !x.equals(cv))
                .reduce(BigInteger.ONE, BigInteger::multiply);
        BigInteger denominator = potentialValues.stream().map(BigInteger::valueOf)
                .filter(x -> !x.equals(cv))
                .reduce(BigInteger.ONE, (prev, x) -> prev.multiply(x.subtract(cv)));
        log.debug("beta values: {}/{} = {}", nominator, denominator, nominator.divideAndRemainder(denominator));
        return nominator.divide(denominator);
    }

    public LinearSignatureData partialProof(LinearSignatureData data, int secret) {
        PublicData publicData = publicParameters.getLinearPublicData(data.getSubstationID(), data.getFid());
        log.debug("PublicParameters: {}", publicData);
        BigInteger eN = publicData.getN().multiply(publicData.getFidPrime());
        BigInteger s = new BigInteger(eN.bitLength(), random).mod(eN);
//      Compute the xi,R with nonce and secret
        BigInteger xR = data.getNonceData().getNonce().add(BigInteger.valueOf(secret));
//      x^(eN) = {g^s * PRODUCT( h[j]^f[j,i] ) * g1^(xR)} mod nRoof
        BigInteger xeN = publicData.getG1().modPow(s, publicData.getNRoof())
                .multiply(publicData.getH()[data.getClientID()])
                .multiply(publicData.getG2().modPow(xR, publicData.getNRoof()))
                .mod(publicData.getNRoof());
//      Solve for x using z as the inverse of eN in mod(totient{NRoof})
//      (eN)z = 1 mod totient(NRoof) ==> (x^(eN))^z = x mod NRoof.
        BigInteger totientNRoof = publicData.getSk()[0].subtract(BigInteger.ONE).multiply(publicData.getSk()[1].subtract(BigInteger.ONE));
        BigInteger eNInverse = publicData.getN().multiply(publicData.getFidPrime()).modInverse(totientNRoof);
//      x = x^(eN)^(eNInverse) mod (NRoof)
        BigInteger x = xeN.modPow(eNInverse, publicData.getNRoof());
        data.setVerifierData(publicData.getFidPrime(), s, x);
        return data;
    }
}

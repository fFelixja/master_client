package com.master_thesis.client;

import ch.qos.logback.classic.Logger;
import com.master_thesis.client.data.Construction;
import com.master_thesis.client.data.HomomorphicHashData;
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


@Component("hash")
public class HomomorphicHash {

    protected PublicParameters publicParameters;
    private static final Logger log = (Logger) LoggerFactory.getLogger(HomomorphicHash.class);
    private final SecureRandom random;

    @Autowired
    public HomomorphicHash(PublicParameters publicParameters) {
        this.publicParameters = publicParameters;
        random = new SecureRandom();
    }

    public HomomorphicHashData shareSecret(int int_secret) {
        int substationID = publicParameters.getSubstationID();
        BigInteger fieldBase = publicParameters.getFieldBase(substationID);
        BigInteger generator = publicParameters.getGenerator(substationID);
        BigInteger secret = BigInteger.valueOf(int_secret);

        BigInteger nonce = BigInteger.valueOf(random.nextLong()).mod(fieldBase);
        log.info("base: {}, generator: {}, secret: {}, nonce: {}", fieldBase, generator, secret, nonce);
        BigInteger proofComponent = hash(fieldBase, secret.add(nonce), generator);

        Function<Integer, BigInteger> polynomial = generatePolynomial(int_secret, fieldBase);
        List<Server> servers = publicParameters.getServers();
        Set<Integer> polynomialInput = IntStream.range(1,servers.size() + 1).boxed().collect(Collectors.toSet());
        Iterator<Integer> iteratorPolyInput = polynomialInput.iterator();
        Map<URI, BigInteger> shares = new HashMap<>();
        servers.forEach(server -> {
            int number = iteratorPolyInput.next();
            BigInteger share = polynomial.apply(number);
            share = share.multiply(beta(number, polynomialInput));
            shares.put(server.getUri().resolve(Construction.HASH.getEndpoint()), share);
        });
        return new HomomorphicHashData(shares, proofComponent, nonce);
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

    public BigInteger hash(BigInteger field, BigInteger input, BigInteger g) {
        return g.modPow(input, field);
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

}

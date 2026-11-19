package org.noemacrypt.geocoinusb;

import org.jocl.CL;
import org.jocl.Pointer;
import org.jocl.Sizeof;
import org.jocl.cl_command_queue;
import org.jocl.cl_context;
import org.jocl.cl_device_id;
import org.jocl.cl_kernel;
import org.jocl.cl_mem;
import org.jocl.cl_platform_id;
import org.jocl.cl_program;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntSupplier;
import java.util.function.LongConsumer;

/** OpenCL GeoPoW worker, executing the SHA-256 search in the selected device. */
final class OpenClGeoPow {
    private OpenClGeoPow() { }

    static List<Device> devices() {
        CL.setExceptionsEnabled(true);
        int[] count = new int[1];
        CL.clGetPlatformIDs(0, null, count);
        cl_platform_id[] platforms = new cl_platform_id[count[0]];
        CL.clGetPlatformIDs(platforms.length, platforms, null);
        List<Device> result = new ArrayList<>();
        for (cl_platform_id platform : platforms) {
            try {
                CL.clGetDeviceIDs(platform, CL.CL_DEVICE_TYPE_GPU, 0, null, count);
                cl_device_id[] found = new cl_device_id[count[0]];
                CL.clGetDeviceIDs(platform, CL.CL_DEVICE_TYPE_GPU, found.length, found, null);
                for (cl_device_id device : found) result.add(new Device(result.size(), device, deviceString(device)));
            } catch (RuntimeException ignored) {
                // A platform without GPU devices reports CL_DEVICE_NOT_FOUND.
            }
        }
        return result;
    }

    private static String deviceString(cl_device_id device) {
        long[] size = new long[1];
        CL.clGetDeviceInfo(device, CL.CL_DEVICE_NAME, 0, null, size);
        byte[] bytes = new byte[(int) size[0]];
        CL.clGetDeviceInfo(device, CL.CL_DEVICE_NAME, bytes.length, Pointer.to(bytes), null);
        int end = bytes.length;
        while (end > 0 && bytes[end - 1] == 0) end--;
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }

    static Long mine(byte[] header, BigInteger target, int deviceId, long startingNonce, long nonceStride,
                     AtomicBoolean active, AtomicBoolean cancelled, IntSupplier gpuLimit,
                     long deadlineNanos, LongConsumer rateUpdate) throws IOException, InterruptedException {
        List<Device> devices = devices();
        if (deviceId < 0 || deviceId >= devices.size()) throw new IOException("OpenCL GPU id is no longer available; scan devices again");
        Device selected = devices.get(deviceId);
        String source;
        try (InputStream in = OpenClGeoPow.class.getResourceAsStream("/opencl/geopow.cl")) {
            if (in == null) throw new IOException("OpenCL kernel resource is missing from the plugin JAR");
            source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        cl_context context = null;
        cl_command_queue queue = null;
        cl_program program = null;
        cl_kernel kernel = null;
        cl_mem dHeader = null, dTarget = null, dFound = null, dWinner = null;
        try {
            context = CL.clCreateContext(null, 1, new cl_device_id[]{selected.handle}, null, null, null);
            queue = CL.clCreateCommandQueue(context, selected.handle, 0, null);
            program = CL.clCreateProgramWithSource(context, 1, new String[]{source}, null, null);
            int build = CL.clBuildProgram(program, 1, new cl_device_id[]{selected.handle}, "", null, null);
            if (build != CL.CL_SUCCESS) throw new IOException("OpenCL kernel compilation failed (error " + build + "): " + buildLog(program, selected.handle));
            kernel = CL.clCreateKernel(program, "geopow", null);
            int[] targetWords = toTargetWords(target);
            dHeader = CL.clCreateBuffer(context, CL.CL_MEM_READ_ONLY | CL.CL_MEM_COPY_HOST_PTR,
                    header.length, Pointer.to(header), null);
            dTarget = CL.clCreateBuffer(context, CL.CL_MEM_READ_ONLY | CL.CL_MEM_COPY_HOST_PTR,
                    (long) targetWords.length * Sizeof.cl_uint, Pointer.to(targetWords), null);
            dFound = CL.clCreateBuffer(context, CL.CL_MEM_READ_WRITE, Sizeof.cl_int, null, null);
            dWinner = CL.clCreateBuffer(context, CL.CL_MEM_READ_WRITE, Sizeof.cl_ulong, null, null);

            setMem(kernel, 0, dHeader); setMem(kernel, 1, dTarget);
            setMem(kernel, 5, dFound); setMem(kernel, 6, dWinner);
            long base = startingNonce;
            long total = 0;
            long start = System.nanoTime();
            final long batch = 65_536;
            while (active.get() && !cancelled.get() && System.nanoTime() < deadlineNanos) {
                int percent = Math.max(0, Math.min(100, gpuLimit.getAsInt()));
                if (percent == 0) {
                    rateUpdate.accept(0);
                    TimeUnit.MILLISECONDS.sleep(100);
                    continue;
                }
                int[] zero = {0};
                CL.clEnqueueWriteBuffer(queue, dFound, CL.CL_TRUE, 0, Sizeof.cl_int, Pointer.to(zero), 0, null, null);
                long[] baseArg = {base}; long[] attemptsArg = {batch};
                CL.clSetKernelArg(kernel, 2, Sizeof.cl_ulong, Pointer.to(baseArg));
                CL.clSetKernelArg(kernel, 3, Sizeof.cl_ulong, Pointer.to(attemptsArg));
                long[] strideArg = {nonceStride};
                CL.clSetKernelArg(kernel, 4, Sizeof.cl_ulong, Pointer.to(strideArg));
                long[] global = {batch}; long[] local = {256};
                long launchStart = System.nanoTime();
                CL.clEnqueueNDRangeKernel(queue, kernel, 1, null, global, local, 0, null, null);
                CL.clFinish(queue);
                long launchNanos = Math.max(1, System.nanoTime() - launchStart);
                int[] found = {0};
                CL.clEnqueueReadBuffer(queue, dFound, CL.CL_TRUE, 0, Sizeof.cl_int, Pointer.to(found), 0, null, null);
                total += batch;
                long elapsed = Math.max(1, (System.nanoTime() - start) / 1_000_000L);
                rateUpdate.accept(total * 1000L / elapsed);
                if (found[0] != 0) {
                    long[] winner = {0};
                    CL.clEnqueueReadBuffer(queue, dWinner, CL.CL_TRUE, 0, Sizeof.cl_ulong, Pointer.to(winner), 0, null, null);
                    return winner[0];
                }
                base += batch * nonceStride;
                long pauseNanos = launchNanos * (100L - percent) / percent;
                while (pauseNanos > 0 && active.get() && !cancelled.get() && System.nanoTime() < deadlineNanos) {
                    long part = Math.min(pauseNanos, TimeUnit.MILLISECONDS.toNanos(100));
                    TimeUnit.NANOSECONDS.sleep(part);
                    pauseNanos -= part;
                }
            }
            return null;
        } catch (RuntimeException e) {
            throw new IOException("OpenCL mining failed: " + e.getMessage(), e);
        } finally {
            if (dWinner != null) CL.clReleaseMemObject(dWinner);
            if (dFound != null) CL.clReleaseMemObject(dFound);
            if (dTarget != null) CL.clReleaseMemObject(dTarget);
            if (dHeader != null) CL.clReleaseMemObject(dHeader);
            if (kernel != null) CL.clReleaseKernel(kernel);
            if (program != null) CL.clReleaseProgram(program);
            if (queue != null) CL.clReleaseCommandQueue(queue);
            if (context != null) CL.clReleaseContext(context);
        }
    }

    private static void setMem(cl_kernel kernel, int index, cl_mem mem) {
        CL.clSetKernelArg(kernel, index, Sizeof.cl_mem, Pointer.to(mem));
    }

    private static int[] toTargetWords(BigInteger target) {
        byte[] raw = target.toByteArray();
        byte[] padded = new byte[32];
        int copy = Math.min(raw.length, 32);
        System.arraycopy(raw, raw.length - copy, padded, 32 - copy, copy);
        int[] words = new int[8];
        for (int i = 0; i < 8; i++) {
            int p = i * 4;
            words[i] = ((padded[p] & 0xff) << 24) | ((padded[p + 1] & 0xff) << 16) |
                    ((padded[p + 2] & 0xff) << 8) | (padded[p + 3] & 0xff);
        }
        return words;
    }

    private static String buildLog(cl_program program, cl_device_id device) {
        long[] size = new long[1];
        CL.clGetProgramBuildInfo(program, device, CL.CL_PROGRAM_BUILD_LOG, 0, null, size);
        byte[] bytes = new byte[(int) size[0]];
        CL.clGetProgramBuildInfo(program, device, CL.CL_PROGRAM_BUILD_LOG, bytes.length, Pointer.to(bytes), null);
        return new String(bytes, StandardCharsets.UTF_8).trim();
    }

    record Device(int id, cl_device_id handle, String name) { }
}


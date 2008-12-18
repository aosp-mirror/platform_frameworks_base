#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/mman.h>
#include <unistd.h>
#include <fcntl.h>

static void *map_memory(const char *fn, unsigned base, unsigned size)
{
    int fd;
    void *ptr;
    
    fd = open(fn, O_RDWR | O_SYNC);
    if(fd < 0) {
        perror("cannot open %s for mapping");
        return MAP_FAILED;
    }

    ptr = mmap(0, size, PROT_READ | PROT_WRITE,
               MAP_SHARED, fd, base);
    close(fd);
    
    if(ptr == MAP_FAILED) {
        fprintf(stderr,"cannot map %s (@%08x,%08x)\n", fn, base, size);
    }
    return ptr;    
}


int main(int argc, char** argv)
{
    void *grp_regs = map_memory("/dev/hw3d", 0, 1024 * 1024);
    printf("GPU base mapped at %p\n", grp_regs);
    int state_offset = 0x10140;
    printf("GPU state = %08lx\n",
            *((long*)((char*)grp_regs + state_offset))  );

    return 0;
}
